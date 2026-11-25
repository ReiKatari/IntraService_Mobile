package com.lantaservice.intra

import com.lantaservice.intra.data.model.TaskListResponseDto
import com.lantaservice.intra.data.model.TaskStatusDto
import com.lantaservice.intra.data.model.TaskStatusSimpleViewDto
import com.lantaservice.intra.data.model.UpdateTaskRequestDto
import com.lantaservice.intra.data.model.extractFileTokens
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.IntraResilientDns
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.CredentialsPayloadCodec
import com.lantaservice.intra.data.storage.SavedCredentials
import com.lantaservice.intra.data.update.AppUpdateManager
import com.lantaservice.intra.ui.login.LoginViewModel
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.FrameQualityAnalyzer
import com.lantaservice.intra.util.LightStatus
import com.lantaservice.intra.util.PhotoEnhancerEngine
import com.lantaservice.intra.util.SharpnessStatus
import com.lantaservice.intra.util.StabilityStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class IntraLogicTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun basicAuthMatchesIntraDocumentationExample() {
        val header = SessionCredentialsProvider.buildBasicAuthHeader("ivanov", "myivanov23")
        assertEquals("Basic aXZhbm92Om15aXZhbm92MjM=", header)
    }

    @Test
    fun fixedServerUrlIsLockedToIntraLantaservice() {
        assertEquals("https://intra.lantaservice.com", SessionCredentialsProvider.FIXED_BASE_URL)
        assertEquals("intra.lantaservice.com", IntraResilientDns.FIXED_HOST)
    }

    @Test
    fun paginatorSerializerHandlesBothObjectAndArray() {
        val objectJson = """
            {
              "Tasks": [],
              "Paginator": {"Count": 10, "Page": 1, "PageCount": 2, "PageSize": 5, "CountOnPage": 5}
            }
        """.trimIndent()
        val arrayJson = """
            {
              "Tasks": [],
              "Paginator": [{"Count": 42, "Page": 2, "PageCount": 5, "PageSize": 10, "CountOnPage": 10}]
            }
        """.trimIndent()

        val respObj = json.decodeFromString(TaskListResponseDto.serializer(), objectJson)
        val respArr = json.decodeFromString(TaskListResponseDto.serializer(), arrayJson)

        assertEquals(10, respObj.paginator?.count)
        assertEquals(2, respObj.paginator?.pageCount)
        assertEquals(42, respArr.paginator?.count)
        assertEquals(5, respArr.paginator?.pageCount)
    }

    @Test
    fun parseTaskFormHandlesWrappedAndFlatFormats() {
        val wrapped = json.parseToJsonElement(
            """
            {
              "Task": {"Id": 159, "Name": "Сбой роутера", "StatusId": 31},
              "UserTaskRights": {
                "Status": 1,
                "Files": 5,
                "Comment": 1,
                "ToStatuses": [{"Id": 29, "Name": "Починил"}]
              }
            }
            """.trimIndent()
        )
        val flat = json.parseToJsonElement(
            """
            {"Id": 160, "Name": "Замена кабеля", "StatusId": 29}
            """.trimIndent()
        )

        val wrappedForm = IntraRepository.parseTaskForm(wrapped, json)
        val flatForm = IntraRepository.parseTaskForm(flat, json)

        assertEquals(159, wrappedForm.task?.id)
        assertEquals(1, wrappedForm.userTaskRights?.toStatuses?.size)
        assertEquals(160, flatForm.task?.id)
        assertNull(flatForm.userTaskRights)
    }

    @Test
    fun attachmentParserExtractsDelimitedNamesAndImages() {
        val attachments = DateAndTextFormatter.parseAttachments(
            fileIdsRaw = "101, 102",
            fileNamesRaw = "photo_panel.JPG||report.pdf",
            legacyFilesRaw = null,
            baseUrl = SessionCredentialsProvider.FIXED_BASE_URL
        )

        assertEquals(2, attachments.size)
        assertEquals("101", attachments[0].id)
        assertEquals("photo_panel.JPG", attachments[0].name)
        assertTrue(attachments[0].isImage)
        assertEquals("https://intra.lantaservice.com/api/taskfile/101", attachments[0].url)

        assertEquals("102", attachments[1].id)
        assertEquals("report.pdf", attachments[1].name)
        assertFalse(attachments[1].isImage)
    }

    @Test
    fun statusClassificationMatchesRepairedCaseInsensitive() {
        val statuses = listOf(
            TaskStatusDto(id = 1, name = "Открыта", isFinal = false),
            TaskStatusDto(id = 2, name = "В работе", isFinal = false),
            TaskStatusDto(id = 3, name = " Починил ", isFinal = false),
            TaskStatusDto(id = 4, name = "Закрыта", isFinal = true)
        )
        val repairedIds = IntraRepository.findRepairedStatusIds(statuses)

        assertEquals(setOf(3), repairedIds)
        assertTrue(IntraRepository.isRepairedStatus(3, null, repairedIds))
        assertTrue(IntraRepository.isRepairedStatus(null, "починил", repairedIds))
        assertFalse(IntraRepository.isRepairedStatus(2, "В работе", repairedIds))

        val target = IntraRepository.findBestRepairedTarget(
            listOf(
                TaskStatusSimpleViewDto(id = 2, name = "В работе"),
                TaskStatusSimpleViewDto(id = 3, name = "Починил")
            )
        )
        assertNotNull(target)
        assertEquals(3, target?.id)
    }

    @Test
    fun updatePayloadOmitsNullFieldsAndNeverImpersonatesUser() {
        val dto = UpdateTaskRequestDto(
            statusId = 29,
            fileTokens = "1,23",
            comment = null
        )
        val encoded = json.encodeToString(UpdateTaskRequestDto.serializer(), dto)

        assertTrue(encoded.contains("\"StatusId\":29"))
        assertTrue(encoded.contains("\"FileTokens\":\"1,23\""))
        assertFalse(encoded.contains("Comment"))
        assertFalse(encoded.contains("CreatorId"))
        assertFalse(encoded.contains("EditorId"))
    }

    @Test
    fun extractFileTokensHandlesCaseVariations() {
        val el1 = json.parseToJsonElement("""{"FileTokens":"1,23"}""")
        val el2 = json.parseToJsonElement("""{"fileTokens":"45"}""")

        assertEquals("1,23", extractFileTokens(el1))
        assertEquals("45", extractFileTokens(el2))
    }

    @Test
    fun dnsQueryAndResponseParsingDecodesCompressedARecord() {
        val txId = 0x1234
        val query = IntraResilientDns.buildDnsQueryPacket("intra.lantaservice.com", txId)
        assertTrue(query.size > 12)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)

        // Build synthetic DNS response echoing question and compressed pointer answer 0xC0 0x0C -> 89.208.212.47
        val resp = ByteArrayOutputStream()
        resp.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte())) // ID + Flags (response, no error)
        resp.write(byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)) // QD=1, AN=1
        resp.write(query, 12, query.size - 12) // Question section
        resp.write(byteArrayOf(0xC0.toByte(), 0x0C)) // Compressed pointer to QNAME
        resp.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // TYPE A, CLASS IN
        resp.write(byteArrayOf(0x00, 0x00, 0x01, 0x2C)) // TTL 300s
        resp.write(byteArrayOf(0x00, 0x04)) // RDLENGTH = 4
        resp.write(byteArrayOf(89, 208.toByte(), 212.toByte(), 47)) // 89.208.212.47

        val packet = resp.toByteArray()
        val parsed = IntraResilientDns.parseDnsARecords(packet, packet.size, expectedTxId = txId)
        assertEquals(1, parsed.size)
        assertArrayEquals(byteArrayOf(89, 208.toByte(), 212.toByte(), 47), parsed[0])
    }

    @Test
    fun dohJsonParserExtractsIpv4Address() {
        val dohSample = """
            {"Status":0,"Answer":[{"name":"intra.lantaservice.com.","type":1,"TTL":300,"data":"89.208.212.47"}]}
        """.trimIndent()
        val parsed = IntraResilientDns.parseDohJsonARecords(dohSample)
        assertEquals(1, parsed.size)
        assertArrayEquals(byteArrayOf(89, 208.toByte(), 212.toByte(), 47), parsed[0])
    }

    @Test
    fun frameQualityAnalyzerDetectsDarkBlurryMovingAndOptimalFrames() {
        val w = 64
        val h = 48

        // 1. Too dark frame
        val dark = ByteArray(w * h) { 18.toByte() }
        val (darkMetrics, _) = FrameQualityAnalyzer.evaluateFrameQuality(dark, w, h)
        assertEquals(LightStatus.TOO_DARK, darkMetrics.light)
        assertFalse(darkMetrics.isOptimalToShoot)

        // 2. Blurry uniform frame with mid brightness
        val blurry = ByteArray(w * h) { 130.toByte() }
        val (blurMetrics, _) = FrameQualityAnalyzer.evaluateFrameQuality(blurry, w, h)
        assertEquals(LightStatus.OK, blurMetrics.light)
        assertEquals(SharpnessStatus.BLURRY, blurMetrics.sharpness)
        assertFalse(blurMetrics.isOptimalToShoot)

        // 3. Optimal sharp text-like stripe frame
        val sharp = ByteArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if ((x / 3 + y / 3) % 2 == 0) 205.toByte() else 55.toByte()
        }
        val (optMetrics, sample1) = FrameQualityAnalyzer.evaluateFrameQuality(sharp, w, h)
        assertEquals(LightStatus.OK, optMetrics.light)
        assertEquals(SharpnessStatus.OK, optMetrics.sharpness)
        assertEquals(StabilityStatus.STEADY, optMetrics.stability)
        assertTrue(optMetrics.isOptimalToShoot)

        // 4. Shifted/moving frame relative to previous sample
        val shifted = ByteArray(w * h) { 215.toByte() }
        val (movingMetrics, _) = FrameQualityAnalyzer.evaluateFrameQuality(shifted, w, h, prevSample = sample1)
        assertEquals(StabilityStatus.MOVING, movingMetrics.stability)
        assertFalse(movingMetrics.isOptimalToShoot)
    }

    @Test
    fun photoEnhancerIncreasesContrastAndSharpnessWithinValidBounds() {
        val w = 64
        val h = 64
        // Synthetic low-contrast slightly soft text bar pattern
        val lowContrast = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            when {
                x in 16..48 && y in 24..38 -> if ((x / 3) % 2 == 0) 108 else 142
                else -> 136
            }
        }
        val beforeScore = PhotoEnhancerEngine.computeSharpnessScore(lowContrast, w, h)
        val enhanced = PhotoEnhancerEngine.enhanceLuminancePlane(lowContrast, w, h)
        val afterScore = PhotoEnhancerEngine.computeSharpnessScore(enhanced, w, h)

        assertEquals(lowContrast.size, enhanced.size)
        assertTrue("All pixels must stay in 0..255", enhanced.all { it in 0..255 })
        assertTrue("Expected sharpness score to improve ($beforeScore -> $afterScore)", afterScore > beforeScore)
    }

    @Test
    fun updateVersionComparisonHandlesSemverAndVPrefix() {
        assertTrue(AppUpdateManager.compareVersions("1.2.0", "1.1.0") > 0)
        assertTrue(AppUpdateManager.compareVersions("1.10.0", "1.2.0") > 0)
        assertTrue(AppUpdateManager.compareVersions("v2.0", "1.9.9") > 0)
        assertEquals(0, AppUpdateManager.compareVersions("v1.2.0", "1.2.0"))
        assertTrue(AppUpdateManager.compareVersions("1.1.9", "1.2.0") < 0)
    }

    @Test
    fun releaseApiJsonParserExtractsApkAsset() {
        val sampleJson = """
            {
              "tag_name": "v1.3.0",
              "name": "IntraService Mobile 1.3.0",
              "draft": false,
              "body": "Улучшена стабильность",
              "assets": [
                {
                  "name": "IntraService-Mobile-1.3.0.apk",
                  "size": 3145728,
                  "browser_download_url": "https://github.com/ReiKatari/IntraService_Mobile/releases/download/v1.3.0/IntraService-Mobile-1.3.0.apk"
                }
              ]
            }
        """.trimIndent()
        val info = AppUpdateManager.parseReleaseApiJson(sampleJson)
        assertNotNull(info)
        assertEquals("1.3.0", info?.versionName)
        assertEquals("v1.3.0", info?.tagName)
        assertEquals("IntraService Mobile 1.3.0", info?.title)
        assertEquals("Улучшена стабильность", info?.changelog)
        assertEquals(3145728L, info?.sizeBytes)
        assertTrue(info?.downloadUrl?.endsWith(".apk") == true)
    }

    @Test
    fun expandedAssetsHtmlFallbackExtractsApkUrl() {
        val html = """
            <li>
              <a href="/ReiKatari/IntraService_Mobile/releases/download/v1.2.5/IntraService-Mobile-1.2.5.apk" rel="nofollow">
                IntraService-Mobile-1.2.5.apk
              </a>
            </li>
        """.trimIndent()
        val info = AppUpdateManager.parseExpandedAssetsHtml(
            html = html,
            tagName = "v1.2.5",
            repo = "ReiKatari/IntraService_Mobile"
        )
        assertNotNull(info)
        assertEquals("1.2.5", info?.versionName)
        assertEquals(
            "https://github.com/ReiKatari/IntraService_Mobile/releases/download/v1.2.5/IntraService-Mobile-1.2.5.apk",
            info?.downloadUrl
        )
    }

    @Test
    fun setCookieParsingAndFormattingHandlesAspNetAuthCookies() {
        val headers = listOf(
            "ASP.NET_SessionId=abc123; path=/; HttpOnly; SameSite=Lax",
            ".ASPXAUTH=TOKENXYZ987; expires=Wed, 24-Nov-2027 18:00:00 GMT; path=/; HttpOnly"
        )
        val parsed = IntraConnectionManager.parseSetCookiesHeader(headers)
        assertEquals("abc123", parsed["ASP.NET_SessionId"])
        assertEquals("TOKENXYZ987", parsed[".ASPXAUTH"])

        val formatted = IntraConnectionManager.formatCookieHeader(parsed)
        assertEquals("ASP.NET_SessionId=abc123; .ASPXAUTH=TOKENXYZ987", formatted)
    }

    @Test
    fun extractEmailLocalPartParsesCorporateEmailsCleanly() {
        assertEquals("p.stasyuk", IntraRepository.extractEmailLocalPart("p.stasyuk@lantaservice.com"))
        assertNull(IntraRepository.extractEmailLocalPart("ivanov"))
        assertNull(IntraRepository.extractEmailLocalPart("@lantaservice.com"))
    }

    @Test
    fun credentialsPayloadCodecPreservesEmailAndSpecialPassword() {
        val original = SavedCredentials(
            login = "p.stasyuk@lantaservice.com",
            password = "P@ss|word:123 Пароль",
            rememberMe = true
        )
        val encoded = CredentialsPayloadCodec.encode(original)
        val decoded = CredentialsPayloadCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("p.stasyuk@lantaservice.com", decoded?.login)
        assertEquals("P@ss|word:123 Пароль", decoded?.password)
        assertEquals(true, decoded?.rememberMe)
    }

    @Test
    fun credentialsPayloadCodecPreservesPartialRememberedInput() {
        val original = SavedCredentials(
            login = "p.stasyuk@lantaservice.com",
            password = "",
            rememberMe = true
        )
        val decoded = CredentialsPayloadCodec.decode(CredentialsPayloadCodec.encode(original))
        assertNotNull(decoded)
        assertEquals("p.stasyuk@lantaservice.com", decoded?.login)
        assertEquals("", decoded?.password)
        assertEquals(true, decoded?.rememberMe)
    }

    @Test
    fun postLoginFieldRetentionMatchesRememberMeFlag() {
        assertEquals(
            "p.stasyuk@lantaservice.com" to "P@ss|1",
            LoginViewModel.resolvePostLoginFields("p.stasyuk@lantaservice.com", "P@ss|1", true)
        )
        assertEquals(
            "" to "",
            LoginViewModel.resolvePostLoginFields("p.stasyuk@lantaservice.com", "P@ss|1", false)
        )
    }

    @Test
    fun dateFormatterFormatsDateOnlyAsDdMmYyyy() {
        assertEquals("25.11.2026", DateAndTextFormatter.formatIntraDate("2026-11-25"))
        assertEquals("25.11.2026", DateAndTextFormatter.formatIntraDate("25.11.2026"))
        assertEquals("05.04.2026", DateAndTextFormatter.formatIntraDate("5.4.2026"))
        assertEquals("25.11.2026", DateAndTextFormatter.formatDeadline("2026-11-25"))
        assertEquals("Без срока", DateAndTextFormatter.formatDeadline(null))
        assertEquals("25.11.2026", DateAndTextFormatter.formatDateOnly("2026-11-25T14:30:00"))
    }

    @Test
    fun dateFormatterFormatsDateTimeAsDdMmYyyyHhMmIncludingNetFractionsAndToday() {
        assertEquals("25.11.2026 14:30", DateAndTextFormatter.formatIntraDate("2026-11-25T14:30:00"))
        assertEquals("25.11.2026 09:05", DateAndTextFormatter.formatIntraDate("2026-11-25T09:05:12.1234567"))
        assertEquals("25.11.2026 18:45", DateAndTextFormatter.formatIntraDate("2026-11-25T18:45:00+03:00"))
        assertEquals("25.11.2026 09:05", DateAndTextFormatter.formatIntraDate("25.11.2026 9:05:33"))
        assertEquals("25.11.2026 14:30", DateAndTextFormatter.formatDeadline("2026-11-25T14:30:00"))

        val nowIso = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val formattedToday = DateAndTextFormatter.formatIntraDate(nowIso)
        assertFalse(formattedToday.contains("Сегодня"))
        assertFalse(formattedToday.contains("Вчера"))
        assertTrue(Regex("^\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}$").matches(formattedToday))
    }
}
