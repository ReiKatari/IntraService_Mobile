package com.lantaservice.intra

import com.lantaservice.intra.data.geo.GeoAddressSnapshot
import com.lantaservice.intra.data.geo.GeoAntiSpoofSelector
import com.lantaservice.intra.data.geo.GeoCandidate
import com.lantaservice.intra.data.geo.GeoSource
import com.lantaservice.intra.data.geo.ParsedRussianAddress
import com.lantaservice.intra.data.geo.ReverseGeocoderParser
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.PhotoDocumentType
import com.lantaservice.intra.data.model.PhotoDocumentTypeLock
import com.lantaservice.intra.data.model.TaskAttachment
import com.lantaservice.intra.data.model.TaskListResponseDto
import com.lantaservice.intra.data.model.TaskSaveValidator
import com.lantaservice.intra.data.model.TaskStatusDto
import com.lantaservice.intra.data.model.TaskStatusSimpleViewDto
import com.lantaservice.intra.data.model.TicketItem
import com.lantaservice.intra.data.model.UpdateTaskRequestDto
import com.lantaservice.intra.data.model.extractFileTokens
import com.lantaservice.intra.data.model.extractFileTokensFromRaw
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.data.network.IntraResilientDns
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.CredentialsPayloadCodec
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.data.storage.PendingOutboxLogic
import com.lantaservice.intra.data.storage.PendingOutboxPhoto
import com.lantaservice.intra.data.storage.SavedCredentials
import com.lantaservice.intra.data.update.AppUpdateInfo
import com.lantaservice.intra.data.update.AppUpdateManager
import com.lantaservice.intra.data.update.UpdateUiState
import com.lantaservice.intra.data.worker.DeadlineUrgencyDetector
import com.lantaservice.intra.ui.components.ReleaseNoteBlock
import com.lantaservice.intra.ui.components.parseReleaseNotes
import com.lantaservice.intra.ui.login.LoginViewModel
import com.lantaservice.intra.util.AvrCounterParser
import com.lantaservice.intra.util.CounterConfidence
import com.lantaservice.intra.util.DateAndTextFormatter
import com.lantaservice.intra.util.DocumentGeometry
import com.lantaservice.intra.util.DocumentQuad
import com.lantaservice.intra.util.DocumentQualityIssue
import com.lantaservice.intra.util.EquipmentScanParser
import com.lantaservice.intra.util.FrameQualityAnalyzer
import com.lantaservice.intra.util.Point2D
import com.lantaservice.intra.util.LightStatus
import com.lantaservice.intra.util.LocationAccessStatus
import com.lantaservice.intra.util.LocationPermissionHelper
import com.lantaservice.intra.util.PhotoEnhancerEngine
import com.lantaservice.intra.util.PrinterModelCatalogEngine
import com.lantaservice.intra.util.SharpnessStatus
import com.lantaservice.intra.util.StabilityStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
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
              "html_url": "https://github.com/ReiKatari/IntraService_Mobile/releases/tag/v1.3.0",
              "published_at": "2026-11-25T14:18:41Z",
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
        assertEquals("25.11.2026", info?.publishedDateText)
        assertEquals("https://github.com/ReiKatari/IntraService_Mobile/releases/tag/v1.3.0", info?.releasePageUrl)
        assertTrue(info?.downloadUrl?.endsWith(".apk") == true)
    }

    @Test
    fun releaseNotesMarkdownParserExtractsHeadingsBulletsTablesAndDividers() {
        val md = """
            ## Что нового
            - **Ускорение**: быстрый запуск
            ---
            | Версия | Статус |
            | --- | --- |
            | 1.8.0 | Готово |
            Обычный текст
        """.trimIndent()
        val blocks = parseReleaseNotes(md)
        assertEquals(6, blocks.size)
        assertEquals(ReleaseNoteBlock.Heading("Что нового", 2), blocks[0])
        assertEquals(ReleaseNoteBlock.Bullet("**Ускорение**: быстрый запуск"), blocks[1])
        assertEquals(ReleaseNoteBlock.Divider, blocks[2])
        assertEquals(ReleaseNoteBlock.TableRow(listOf("Версия", "Статус")), blocks[3])
        assertEquals(ReleaseNoteBlock.TableRow(listOf("1.8.0", "Готово")), blocks[4])
        assertEquals(ReleaseNoteBlock.Paragraph("Обычный текст"), blocks[5])
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
        assertEquals("engineer", IntraRepository.extractEmailLocalPart("engineer@lantaservice.com"))
        assertNull(IntraRepository.extractEmailLocalPart("ivanov"))
        assertNull(IntraRepository.extractEmailLocalPart("@lantaservice.com"))
    }

    @Test
    fun credentialsPayloadCodecPreservesEmailAndSpecialPassword() {
        val original = SavedCredentials(
            login = "engineer@lantaservice.com",
            password = "P@ss|word:123 Пароль",
            rememberMe = true
        )
        val encoded = CredentialsPayloadCodec.encode(original)
        val decoded = CredentialsPayloadCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("engineer@lantaservice.com", decoded?.login)
        assertEquals("P@ss|word:123 Пароль", decoded?.password)
        assertEquals(true, decoded?.rememberMe)
    }

    @Test
    fun credentialsPayloadCodecPreservesPartialRememberedInput() {
        val original = SavedCredentials(
            login = "engineer@lantaservice.com",
            password = "",
            rememberMe = true
        )
        val decoded = CredentialsPayloadCodec.decode(CredentialsPayloadCodec.encode(original))
        assertNotNull(decoded)
        assertEquals("engineer@lantaservice.com", decoded?.login)
        assertEquals("", decoded?.password)
        assertEquals(true, decoded?.rememberMe)
    }

    @Test
    fun postLoginFieldRetentionMatchesRememberMeFlag() {
        assertEquals(
            "engineer@lantaservice.com" to "P@ss|1",
            LoginViewModel.resolvePostLoginFields("engineer@lantaservice.com", "P@ss|1", true)
        )
        assertEquals(
            "" to "",
            LoginViewModel.resolvePostLoginFields("engineer@lantaservice.com", "P@ss|1", false)
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

    @Test
    fun extractFileTokensFromRawHandlesJsonStringArrayAndXml() {
        assertEquals("1,23", extractFileTokensFromRaw("""{"FileTokens":"1,23"}""", json))
        assertEquals("45", extractFileTokensFromRaw("""{"fileTokens":"45"}""", json))
        assertEquals("77,88", extractFileTokensFromRaw("[77, 88]", json))
        assertEquals("99", extractFileTokensFromRaw("\"99\"", json))
        assertEquals("11,22", extractFileTokensFromRaw("<Response><FileTokens>11,22</FileTokens></Response>", json))
    }

    @Test
    fun recoverableOutboxFailureClassifierDistinguishesOfflineFromValidationErrors() {
        assertTrue(IntraRepository.isRecoverableOutboxFailure(SocketTimeoutException("timeout")))
        assertTrue(IntraRepository.isRecoverableOutboxFailure(IOException("network down")))
        assertTrue(IntraRepository.isRecoverableOutboxFailure(IllegalStateException("503"), httpCode = 503))
        assertTrue(IntraRepository.isRecoverableOutboxFailure(IllegalStateException("429"), httpCode = 429))
        assertFalse(IntraRepository.isRecoverableOutboxFailure(IllegalStateException("400"), httpCode = 400))
        assertFalse(IntraRepository.isRecoverableOutboxFailure(IllegalStateException("403"), httpCode = 403))
        assertFalse(IntraRepository.isRecoverableOutboxFailure(IllegalStateException("409"), httpCode = 409))
    }

    @Test
    fun deferredCommentAndPhotoNameEmbedFixedTimestamp() {
        assertEquals(
            "[Отложенная отправка: попытка от 25.11.2026 16:40]",
            IntraRepository.buildDeferredComment("", "25.11.2026 16:40")
        )
        assertEquals(
            "Заменил блок питания\n\n[Отложенная отправка: попытка от 25.11.2026 16:40]",
            IntraRepository.buildDeferredComment("Заменил блок питания", "25.11.2026 16:40")
        )
        assertEquals(
            "task159_25112026_1640_1",
            IntraRepository.buildDeferredPhotoName(159, "25.11.2026 16:40", 0)
        )
    }

    @Test
    fun pendingOutboxLogicUpsertsUpdatesSameTaskAndIsolatesUsers() {
        val e1 = PendingOutboxEntry(
            taskId = 101,
            userId = 1,
            newStatusId = 29,
            comment = "Первая попытка",
            photos = listOf(PendingOutboxPhoto("p1", "/tmp/p1.jpg", "p1", 100L)),
            recordedAtEpochMs = 1000L,
            recordedAtText = "25.11.2026 16:30"
        )
        val e2OtherUser = PendingOutboxEntry(
            taskId = 101,
            userId = 2,
            newStatusId = 29,
            comment = "Другой инженер",
            recordedAtEpochMs = 1500L,
            recordedAtText = "25.11.2026 16:35"
        )
        val e1Updated = e1.copy(
            comment = "Новые данные и статус",
            photos = listOf(PendingOutboxPhoto("p2", "/tmp/p2.jpg", "p2", 200L)),
            recordedAtEpochMs = 2000L,
            recordedAtText = "25.11.2026 16:45"
        )

        var list = PendingOutboxLogic.upsert(emptyList(), e1)
        list = PendingOutboxLogic.upsert(list, e2OtherUser)
        list = PendingOutboxLogic.upsert(list, e1Updated)

        assertEquals(2, list.size)
        val user1 = PendingOutboxLogic.filterForUser(list, 1)
        assertEquals(1, user1.size)
        assertEquals("Новые данные и статус", user1[0].comment)
        assertEquals("25.11.2026 16:45", user1[0].recordedAtText)
        assertEquals("p2", user1[0].photos[0].id)

        val afterRemove = PendingOutboxLogic.remove(list, 101, 1)
        assertEquals(0, PendingOutboxLogic.filterForUser(afterRemove, 1).size)
        assertEquals(1, PendingOutboxLogic.filterForUser(afterRemove, 2).size)
    }

    @Test
    fun parseLifetimeFileNamesExtractsNamesFromIdPipePairs() {
        assertEquals(
            listOf("photo_1.jpg", "act.pdf"),
            DateAndTextFormatter.parseLifetimeFileNames("101|photo_1.jpg, 102|act.pdf")
        )
        assertEquals(
            listOf("file.png", "doc.docx"),
            DateAndTextFormatter.parseLifetimeFileNames("55|file.png||56|doc.docx")
        )
        assertEquals(
            listOf("plain.jpg"),
            DateAndTextFormatter.parseLifetimeFileNames("plain.jpg")
        )
    }

    @Test
    fun parseLifetimeEventRendersAllChangeTypesAndNeverShowsEmptyBody() {
        val rawObj = json.parseToJsonElement(
            """
            {
              "Date": "2026-11-25T17:01:00",
              "Editor": "Петров П.П.",
              "StatusId": 29,
              "Comments": "<p>Кабель заменен</p>",
              "IsPublic": true,
              "AddedFiles": "101|photo_rack.jpg",
              "RemovedFiles": "99|old.jpg",
              "Executors": "Иванов И.И.",
              "ExecutorsGroup": "Выездная бригада",
              "PriorityId": 3,
              "ServiceId": 7,
              "Deadline": "2026-11-26T18:00:00",
              "Field47": "Щиток #2"
            }
            """.trimIndent()
        ).jsonObject

        val ev = IntraRepository.parseLifetimeEvent(
            obj = rawObj,
            statusesById = mapOf(29 to TaskStatusDto(id = 29, name = "Починил")),
            prioritiesById = mapOf(3 to com.lantaservice.intra.data.model.TaskPriorityDto(id = 3, name = "Высокий")),
            servicesById = mapOf(7 to "Интернет")
        )

        assertEquals("25.11.2026 17:01", ev.dateText)
        assertEquals("Петров П.П.", ev.editorName)
        assertEquals("Починил", ev.statusName)
        assertEquals("Кабель заменен", ev.comment)
        assertEquals(true, ev.isPublic)
        assertEquals(listOf("photo_rack.jpg"), ev.addedFiles)
        assertEquals(listOf("old.jpg"), ev.removedFiles)
        assertTrue(ev.changes.contains("Исполнители: Иванов И.И."))
        assertTrue(ev.changes.contains("Группа исполнителей: Выездная бригада"))
        assertTrue(ev.changes.contains("Приоритет: Высокий"))
        assertTrue(ev.changes.contains("Сервис: Интернет"))
        assertTrue(ev.changes.contains("Срок: 26.11.2026 18:00"))
        assertTrue(ev.changes.contains("Field47: Щиток #2"))

        val emptyObj = json.parseToJsonElement("""{"Date":"2026-11-25T17:02:00","Editor":"Иванов И.И."}""").jsonObject
        val emptyEv = IntraRepository.parseLifetimeEvent(emptyObj)
        assertEquals(listOf("Обновление заявки"), emptyEv.changes)
    }

    @Test
    fun geoAntiSpoofSelectorHandlesClearSkySpoofJumpJammerAndDeadZone() {
        val now = 1_760_000_000_000L
        val moscowGps = GeoCandidate(55.7558, 37.6173, 12f, now, "gps")
        val moscowNet = GeoCandidate(55.7562, 37.6180, 45f, now, "network")

        // 1. Clean GPS selected under normal conditions
        val clean = GeoAntiSpoofSelector.selectBestCandidate(listOf(moscowGps, moscowNet), null, nowMs = now)
        assertNotNull(clean)
        assertEquals(GeoSource.GPS, clean?.source)
        assertFalse(clean?.jammerSuspected ?: true)

        // 2. Spoofed GPS (teleported 50 km away) rejected in favor of Wi-Fi/Cellular network fix
        val spoofedGps = GeoCandidate(56.2000, 38.1000, 8f, now, "gps")
        val antiSpoof = GeoAntiSpoofSelector.selectBestCandidate(listOf(spoofedGps, moscowNet), moscowGps, nowMs = now)
        assertNotNull(antiSpoof)
        assertEquals(GeoSource.NETWORK_ANTI_JAMMER, antiSpoof?.source)
        assertTrue(antiSpoof?.jammerSuspected == true)

        // 3. GPS jammed (absent) -> falls back to Wi-Fi/Cell triangulation
        val jammedGps = GeoAntiSpoofSelector.selectBestCandidate(listOf(moscowNet), moscowGps, nowMs = now)
        assertNotNull(jammedGps)
        assertEquals(GeoSource.NETWORK_WIFI_CELL, jammedGps?.source)

        // 4. Total dead zone inside bunker -> pre-entry breadcrumb within 2h used
        val deadZone = GeoAntiSpoofSelector.selectBestCandidate(
            candidates = emptyList(),
            lastVerified = moscowGps.copy(timestampMs = now - 5 * 60_000L),
            exifCandidate = null,
            nowMs = now
        )
        assertNotNull(deadZone)
        assertEquals(GeoSource.PRE_ENTRY_CACHE, deadZone?.source)
        assertTrue(deadZone?.jammerSuspected == true)
    }

    @Test
    fun reverseGeocoderParserFormatsFullRussianAddressFromNominatimAndPhoton() {
        val nominatimRaw = """
            {
              "address": {
                "postcode": "125009",
                "country": "Россия",
                "state": "Москва",
                "city": "Москва",
                "road": "Тверская улица",
                "house_number": "7"
              }
            }
        """.trimIndent()
        val nominatim = ReverseGeocoderParser.parseNominatimJson(nominatimRaw, json)
        assertNotNull(nominatim)
        assertEquals("125009", nominatim?.postalCode)
        assertEquals("Москва", nominatim?.region)
        assertEquals("125009, Москва, Тверская улица, д. 7", nominatim?.fullAddress)

        val photonRaw = """
            {
              "features": [
                {
                  "properties": {
                    "postcode": "630099",
                    "country": "Россия",
                    "state": "Новосибирская область",
                    "city": "Новосибирск",
                    "street": "Красный проспект",
                    "housenumber": "25"
                  }
                }
              ]
            }
        """.trimIndent()
        val photon = ReverseGeocoderParser.parsePhotonJson(photonRaw, json)
        assertNotNull(photon)
        assertEquals(
            "630099, Новосибирская область, Новосибирск, Красный проспект, д. 25",
            photon?.fullAddress
        )
    }

    @Test
    fun photoDocumentTypeLockEnforces5MinuteWindowAndRenaming() {
        val created = 1_000_000L
        assertTrue(PhotoDocumentTypeLock.canChangeType(created, created + 10_000L))
        assertTrue(PhotoDocumentTypeLock.canChangeType(created, created + 299_999L))
        assertFalse(PhotoDocumentTypeLock.canChangeType(created, created + 300_000L))
        assertEquals("4:30", PhotoDocumentTypeLock.formatRemainingMmSs(created, created + 30_000L))
        assertEquals(
            "TestList_task15_20261126_1",
            PhotoDocumentTypeLock.renamePrefix("AVR_task15_20261126_1", PhotoDocumentType.TEST_LIST)
        )
        assertEquals(PhotoDocumentType.AVR, PhotoDocumentTypeLock.detectTypeFromFileName("AVR_task15_1.jpg"))
        assertEquals(PhotoDocumentType.TEST_LIST, PhotoDocumentTypeLock.detectTypeFromFileName("TestList_task15_2.jpg"))
        assertEquals(PhotoDocumentType.CONFIG_LIST, PhotoDocumentTypeLock.detectTypeFromFileName("ConfigList_task15_3.jpg"))
    }

    @Test
    fun avrCounterParserExtractsValidCountersAndRejectsDatesPhonesAndActs() {
        // Explicit Russian label
        val resRu = AvrCounterParser.extractCounterFromLines(
            listOf(
                "АКТ ВЫПОЛНЕННЫХ РАБОТ № 4512 от 26.11.2026",
                "ИНН 7701234567 Тел: +7 999 123-45-67",
                "Счётчик копий: 145 230"
            )
        )
        assertEquals("145230", resRu.value)
        assertEquals(CounterConfidence.HIGH, resRu.confidence)

        // Latin-OCR homoglyph reading of Cyrillic label
        val resHomoglyph = AvrCounterParser.extractCounterFromLines(
            listOf(
                "C4ET4WK KONWW: 84210",
                "26.11.2026 14:30"
            )
        )
        assertEquals("84210", resHomoglyph.value)
        assertEquals(CounterConfidence.HIGH, resHomoglyph.confidence)

        // Tabular two-line layout ("Счетчик" header above value)
        val resNextLine = AvrCounterParser.extractCounterFromLines(
            listOf(
                "Показания счетчика",
                "52 400"
            )
        )
        assertEquals("52400", resNextLine.value)
        assertEquals(CounterConfidence.HIGH, resNextLine.confidence)

        // Configuration page without counter (Lexmark MX622ade "Повышение чёткости шрифта: 24")
        val resConfigPage = AvrCounterParser.extractCounterFromLines(
            listOf(
                "Меню конфигурации",
                "Lexmark MX622ade",
                "Повышение чёткости шрифта: 24",
                "Повышение четкости шрифта 24",
                "Разрешение: 600 dpi",
                "Плотность тонера: 8",
                "Копий: 1",
                "Память: 512 МБ"
            )
        )
        assertNull(resConfigPage.value)
        assertEquals(CounterConfidence.NONE, resConfigPage.confidence)

        // Standalone number with no explicit counter keyword must NEVER be auto-filled
        val resStandalone = AvrCounterParser.extractCounterFromLines(
            listOf("Страница диагностики", "24")
        )
        assertNull(resStandalone.value)
        assertEquals(CounterConfidence.NONE, resStandalone.confidence)

        // Unreadable / blurry / no counter -> returns NONE so UI prompts manual entry
        val resEmpty = AvrCounterParser.extractCounterFromLines(
            listOf(
                "Акт № 981 от 26.11.2026",
                "Телефон: +7 (495) 111-22-33"
            )
        )
        assertNull(resEmpty.value)
        assertEquals(CounterConfidence.NONE, resEmpty.confidence)
    }

    @Test
    fun taskSaveValidatorRequiresAvrTestListAndCounter() {
        val avrPhoto = PendingPhoto(
            id = "1",
            file = File("AVR_1.jpg"),
            nameWithoutExt = "AVR_1",
            sizeBytes = 100L,
            documentType = PhotoDocumentType.AVR
        )
        val testPhoto = PendingPhoto(
            id = "2",
            file = File("TestList_2.jpg"),
            nameWithoutExt = "TestList_2",
            sizeBytes = 100L,
            documentType = PhotoDocumentType.TEST_LIST
        )

        // Missing Test-List and Counter
        val step1 = TaskSaveValidator.evaluate(
            pendingPhotos = listOf(avrPhoto),
            existingAttachments = emptyList(),
            counterValue = "",
            hasChanges = true
        )
        assertFalse(step1.isReadyToSave)
        assertEquals(listOf("Тест-лист", "Счётчик копий"), step1.missingItems())

        // All 3 present -> ready to save
        val ready = TaskSaveValidator.evaluate(
            pendingPhotos = listOf(avrPhoto, testPhoto),
            existingAttachments = emptyList(),
            counterValue = "145230",
            hasChanges = true
        )
        assertTrue(ready.isReadyToSave)
        assertTrue(ready.missingItems().isEmpty())

        // Existing server AVR + pending Test-List + counter -> also ready
        val readyWithExistingAvr = TaskSaveValidator.evaluate(
            pendingPhotos = listOf(testPhoto),
            existingAttachments = listOf(
                TaskAttachment("10", "AVR_task1_old.jpg", true, "https://example.com/10")
            ),
            counterValue = "9910",
            hasChanges = true
        )
        assertTrue(readyWithExistingAvr.isReadyToSave)
    }

    @Test
    fun submissionCommentIncludesCounterDocumentsAndFullGeoAddress() {
        val geo = GeoAddressSnapshot(
            latitude = 55.755800,
            longitude = 37.617300,
            accuracyMeters = 18f,
            capturedAtEpochMs = 1_760_000_000_000L,
            capturedAtText = "26.11.2026 12:10",
            source = GeoSource.NETWORK_ANTI_JAMMER,
            postalCode = "125009",
            region = "Москва",
            locality = "Москва",
            street = "Тверская улица",
            houseNumber = "7",
            fullAddress = "125009, Москва, Тверская улица, д. 7",
            addressResolved = true,
            jammerSuspected = true,
            warningNote = "Обнаружены помехи GPS - использованы Wi-Fi и сотовые вышки"
        )
        val comment = IntraRepository.buildSubmissionComment(
            userComment = "Заменен термоблок",
            counterValue = "145230",
            photos = listOf(
                PendingPhoto("1", File("a.jpg"), "AVR_1", 10L, documentType = PhotoDocumentType.AVR),
                PendingPhoto("2", File("b.jpg"), "TestList_1", 10L, documentType = PhotoDocumentType.TEST_LIST)
            ),
            geoSnapshot = geo,
            deferredRecordedAtText = "26.11.2026 12:10"
        )
        assertTrue(comment.contains("Заменен термоблок"))
        assertTrue(comment.contains("Счётчик копий (АВР): 145 230"))
        assertTrue(comment.contains("Документы: АВР (1), Тест-лист (1)"))
        assertTrue(comment.contains("Геолокация: 125009, Москва, Тверская улица, д. 7"))
        assertTrue(comment.contains("55.755800, 37.617300"))
        assertTrue(comment.contains("[Отложенная отправка: попытка от 26.11.2026 12:10]"))

        assertEquals(
            "AVR_task159_26112026_1210_1",
            IntraRepository.buildDeferredPhotoName(159, "26.11.2026 12:10", 0, PhotoDocumentType.AVR)
        )
    }

    @Test
    fun updateStateTransitionSurfacesUpdateWhileAuthorizedAndProtectsActiveDownloads() {
        val v201 = AppUpdateInfo(
            versionName = "2.0.1",
            tagName = "v2.0.1",
            title = "IntraService Mobile 2.0.1",
            changelog = "Исправлены обновления",
            downloadUrl = "https://example.com/2.0.1.apk"
        )
        val v202 = v201.copy(versionName = "2.0.2", tagName = "v2.0.2")

        // 1. Fresh Idle -> pops up Available dialog
        val fromIdle = AppUpdateManager.resolveNextUpdateState(
            currentState = UpdateUiState.Idle,
            currentlyDismissed = true,
            found = v201,
            currentVersion = "2.0.0",
            skippedVersion = "",
            reopenIfAlreadyAvailable = false
        )
        assertNotNull(fromIdle)
        assertTrue(fromIdle?.first is UpdateUiState.Available)
        assertEquals(false, fromIdle?.second)

        // 2. Same version dismissed, auto background poll -> stays dismissed (banner remains visible)
        val sameAuto = AppUpdateManager.resolveNextUpdateState(
            currentState = UpdateUiState.Available(v201),
            currentlyDismissed = true,
            found = v201,
            currentVersion = "2.0.0",
            skippedVersion = "",
            reopenIfAlreadyAvailable = false
        )
        assertEquals(true, sameAuto?.second)

        // 3. Same version dismissed, explicit login/pull check -> reopens dialog
        val sameReopen = AppUpdateManager.resolveNextUpdateState(
            currentState = UpdateUiState.Available(v201),
            currentlyDismissed = true,
            found = v201,
            currentVersion = "2.0.0",
            skippedVersion = "",
            reopenIfAlreadyAvailable = true
        )
        assertEquals(false, sameReopen?.second)

        // 4. Higher version published while logged in -> pops up dialog immediately
        val newerRelease = AppUpdateManager.resolveNextUpdateState(
            currentState = UpdateUiState.Available(v201),
            currentlyDismissed = true,
            found = v202,
            currentVersion = "2.0.0",
            skippedVersion = "",
            reopenIfAlreadyAvailable = false
        )
        assertEquals(v202, (newerRelease?.first as? UpdateUiState.Available)?.info)
        assertEquals(false, newerRelease?.second)

        // 5. Downloading state is never overwritten back to Available
        val downloading = UpdateUiState.Downloading(v201, 0.5f, 500L, 1000L)
        val duringDownload = AppUpdateManager.resolveNextUpdateState(
            currentState = downloading,
            currentlyDismissed = false,
            found = v201,
            currentVersion = "2.0.0",
            skippedVersion = "",
            reopenIfAlreadyAvailable = false
        )
        assertNull(duringDownload)

        // 6. Skipped version stays ignored
        val skipped = AppUpdateManager.resolveNextUpdateState(
            currentState = UpdateUiState.Idle,
            currentlyDismissed = false,
            found = v201,
            currentVersion = "2.0.0",
            skippedVersion = "2.0.1",
            reopenIfAlreadyAvailable = true
        )
        assertNull(skipped)
    }

    @Test
    fun locationAccessRequiresBothAppPermissionAndSystemLocationToggle() {
        assertEquals(
            LocationAccessStatus.READY,
            LocationPermissionHelper.evaluateLocationAccess(permissionGranted = true, serviceEnabled = true)
        )
        assertEquals(
            LocationAccessStatus.SERVICE_DISABLED,
            LocationPermissionHelper.evaluateLocationAccess(permissionGranted = true, serviceEnabled = false)
        )
        assertEquals(
            LocationAccessStatus.PERMISSION_DENIED,
            LocationPermissionHelper.evaluateLocationAccess(permissionGranted = false, serviceEnabled = true)
        )
        assertEquals(
            LocationAccessStatus.PERMISSION_DENIED,
            LocationPermissionHelper.evaluateLocationAccess(permissionGranted = false, serviceEnabled = false)
        )
    }

    @Test
    fun photoDocumentTypeCaptureButtonsHaveExpectedShortLabels() {
        assertEquals("Снять АВР", PhotoDocumentType.AVR.captureButtonLabel)
        assertEquals("Снять ТЛ", PhotoDocumentType.TEST_LIST.captureButtonLabel)
        assertEquals("Снять ЛК", PhotoDocumentType.CONFIG_LIST.captureButtonLabel)
    }

    @Test
    fun documentGeometryDetectsTiltedA4SheetAndValidatesTiltDistanceAndReadability() {
        // 1. Synthetic white paper sheet on darker warm wooden desk (120 x 160 test frame)
        val w = 120
        val h = 160
        val pixels = IntArray(w * h)
        val wood = (0xFF shl 24) or (145 shl 16) or (120 shl 8) or 92
        val paper = (0xFF shl 24) or (236 shl 16) or (236 shl 8) or 238
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Slightly tilted trapezoid: top width ~66px (x 28..94), bottom width ~78px (x 21..99), y 20..140
                val t = ((y - 20).toFloat() / 120f).coerceIn(0f, 1f)
                val xMin = 28f - t * 7f
                val xMax = 94f + t * 5f
                val inSheet = y in 20..140 && x.toFloat() in xMin..xMax
                pixels[y * w + x] = if (inSheet) paper else wood
            }
        }
        val detected = DocumentGeometry.detectPaperQuadFromArgb(pixels, w, h)
        assertNotNull(detected)
        val fullScaled = detected!!.scaled(10f, 10f)
        val report = DocumentGeometry.evaluateQuadGeometry(fullScaled, w * 10, h * 10)
        assertEquals(DocumentQualityIssue.NONE, report.issue)
        assertTrue(report.canSafelyFlatten)

        // 2. Too far away sheet (< 22% of frame) -> triggers TOO_FAR retake warning
        val farQuad = DocumentQuad(
            Point2D(350f, 400f),
            Point2D(620f, 400f),
            Point2D(620f, 780f),
            Point2D(350f, 780f)
        )
        val farReport = DocumentGeometry.evaluateQuadGeometry(farQuad, 1200, 1600)
        assertEquals(DocumentQualityIssue.TOO_FAR, farReport.issue)
        assertNotNull(farReport.warningMessage)

        // 3. Extreme perspective tilt (top 220px vs bottom 720px -> taper > 3x) -> triggers EXTREME_TILT warning
        val skewedQuad = DocumentQuad(
            Point2D(490f, 150f),
            Point2D(710f, 150f),
            Point2D(960f, 1450f),
            Point2D(240f, 1450f)
        )
        val tiltReport = DocumentGeometry.evaluateQuadGeometry(skewedQuad, 1200, 1600)
        assertEquals(DocumentQualityIssue.EXTREME_TILT, tiltReport.issue)
        assertFalse(tiltReport.canSafelyFlatten)

        // 4. Auto-rotation classification from ML Kit line angles
        assertEquals(0, DocumentGeometry.resolveRotationFromLineAngles(listOf(-4f, 2f, 5f)))
        assertEquals(270, DocumentGeometry.resolveRotationFromLineAngles(listOf(88f, 91f)))
        assertEquals(90, DocumentGeometry.resolveRotationFromLineAngles(listOf(-92f, -89f)))
        assertEquals(180, DocumentGeometry.resolveRotationFromLineAngles(listOf(178f, -179f)))

        // 5. Readability audit: inked sheet where OCR failed (< 14 readable chars) triggers retake warning
        val (unreadable, warnMsg) = DocumentGeometry.evaluateReadability(
            sharpnessScore = 80.0,
            darkInkRatio = 0.04f,
            ocrAlnumCount = 3,
            medianLineHeightPx = 7f,
            geometryReport = report
        )
        assertTrue(unreadable)
        assertNotNull(warnMsg)

        // 6. Crisp readable flattened document passes cleanly
        val (readableFail, okMsg) = DocumentGeometry.evaluateReadability(
            sharpnessScore = 110.0,
            darkInkRatio = 0.04f,
            ocrAlnumCount = 120,
            medianLineHeightPx = 22f,
            geometryReport = report
        )
        assertFalse(readableFail)
        assertNull(okMsg)
    }

    @Test
    fun liveLumaSheetGuidanceDetectsCenteredA4AndPromptsWhenTooFar() {
        val w = 112
        val h = 150
        val luma = ByteArray(w * h)
        // Bright A4 sheet centered on dark tabletop (occupies ~54% area)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onSheet = x in 20..92 && y in 18..132
                luma[y * w + x] = (if (onSheet) 232 else 48).toByte()
            }
        }
        val quad = DocumentGeometry.detectPaperQuadFromLuma(luma, w, h)
        assertNotNull(quad)
        val ready = DocumentGeometry.evaluateLiveSheetGuidance(
            quad = quad,
            frameW = w,
            frameH = h,
            light = LightStatus.OK,
            sharpness = SharpnessStatus.OK,
            stability = StabilityStatus.STEADY
        )
        assertTrue(ready.isSheetAligned)
        assertNotNull(ready.normalizedQuad)
        assertEquals(4, ready.normalizedQuad!!.size)

        // Small sheet (< 22% area) -> prompt to approach closer
        val smallLuma = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onSmallSheet = x in 42..68 && y in 54..92
                smallLuma[y * w + x] = (if (onSmallSheet) 235 else 42).toByte()
            }
        }
        val smallQuad = DocumentGeometry.detectPaperQuadFromLuma(smallLuma, w, h)
        assertNotNull(smallQuad)
        val tooFar = DocumentGeometry.evaluateLiveSheetGuidance(
            quad = smallQuad,
            frameW = w,
            frameH = h,
            light = LightStatus.OK,
            sharpness = SharpnessStatus.OK,
            stability = StabilityStatus.STEADY
        )
        assertFalse(tooFar.isSheetAligned)
        assertTrue(tooFar.hint.contains("ближе", ignoreCase = true))
    }

    @Test
    fun documentScanBwPlaneRemovesShadowAndPreservesDarkInk() {
        val w = 64
        val h = 64
        val yIn = IntArray(w * h)
        for (y in 0 until h) {
            val paperBg = if (y < h / 2) 220 else 108 // Half sheet in deep hand shadow
            for (x in 0 until w) {
                val isInk = (x in 28..35) && (y in 12..52)
                yIn[y * w + x] = if (isInk) 28 else paperBg
            }
        }
        val bw = PhotoEnhancerEngine.enhanceDocumentScanBwPlane(yIn, w, h)
        // Bright & shaded paper backgrounds both normalize near pure white (>= 240)
        assertTrue(bw[10 * w + 10] >= 240)
        assertTrue(bw[54 * w + 10] >= 240)
        // Ink strokes stay dark (<= 55) in both bright and shaded halves
        assertTrue(bw[16 * w + 31] <= 55)
        assertTrue(bw[44 * w + 31] <= 55)
    }

    @Test
    fun equipmentScanParserExtractsSerialModelAndMatchesTicket() {
        val resMatch = EquipmentScanParser.parse(
            barcodeValues = listOf("S/N: 70169412345"),
            ocrLines = listOf("Lexmark MX622ade", "Инв. № INV-4402"),
            ticketText = "Замена термоблока на МФУ Lexmark MX622ade, s/n 70169412345"
        )
        assertNotNull(resMatch)
        assertEquals("70169412345", resMatch!!.serialNumber)
        assertEquals("INV-4402", resMatch.inventoryNumber)
        assertTrue(resMatch.modelName.contains("Lexmark", ignoreCase = true))
        assertEquals(true, resMatch.matchesTicket)

        val submission = IntraRepository.buildSubmissionComment(
            userComment = "Работы выполнены",
            counterValue = "54120",
            equipmentScan = resMatch
        )
        assertTrue(submission.contains("Оборудование:"))
        assertTrue(submission.contains("70169412345"))

        val resMismatch = EquipmentScanParser.parse(
            barcodeValues = listOf("S/N: 99988877766"),
            ocrLines = listOf("Kyocera M2040dn"),
            ticketText = "Ремонт Lexmark MX622ade S/N 70169412345"
        )
        assertNotNull(resMismatch)
        assertEquals(false, resMismatch!!.matchesTicket)
    }

    @Test
    fun deadlineUrgencyDetectorFindsNewTicketsAndUrgentDeadlines() {
        val now = LocalDateTime.of(2026, 11, 26, 14, 0)
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        fun item(id: Int, deadline: String, repaired: Boolean = false) = TicketItem(
            id = id,
            name = "Заявка $id",
            descriptionPreview = "",
            statusId = 26,
            statusName = if (repaired) "Починил" else "В работе",
            priorityId = null,
            priorityName = "Средний",
            serviceName = "Сервис",
            creatorName = "Инженер",
            createdText = "26.11.2026",
            changedText = "26.11.2026 12:00",
            changedRaw = "",
            deadlineText = deadline,
            isOverdue = false,
            attachmentCount = 0,
            hasHadRepairedStatus = repaired,
            isLifetimeChecked = true
        )

        val t1 = item(101, now.plusMinutes(35).format(fmt))
        val t2 = item(102, now.plusMinutes(180).format(fmt))
        val t3 = item(103, now.plusMinutes(20).format(fmt), repaired = true)

        assertTrue(DeadlineUrgencyDetector.findNewTickets(emptySet(), listOf(t1, t2)).isEmpty())
        val newlyAssigned = DeadlineUrgencyDetector.findNewTickets(setOf(101), listOf(t1, t2, t3))
        assertEquals(listOf(102), newlyAssigned.map { it.id })

        val urgent = DeadlineUrgencyDetector.findUrgentDeadlines(
            tickets = listOf(t1, t2, t3),
            now = now,
            alreadyAlertedKeys = emptySet(),
            thresholdMinutes = 60L
        )
        assertEquals(1, urgent.size)
        assertEquals(101, urgent.first().first.id)
        assertEquals(35L, urgent.first().second)

        val suppressed = DeadlineUrgencyDetector.findUrgentDeadlines(
            tickets = listOf(t1),
            now = now,
            alreadyAlertedKeys = setOf("101|${t1.deadlineText}"),
            thresholdMinutes = 60L
        )
        assertTrue(suppressed.isEmpty())
    }

    @Test
    fun reverseGeocoderParserExtractsSuburbPlaceNameAndMergesAddresses() {
        val nominatimRaw = """
            {
              "name": "БЦ Омега Плаза",
              "address": {
                "postcode": "115280",
                "country": "Россия",
                "state": "Москва",
                "suburb": "Даниловский район",
                "city": "Москва",
                "road": "Ленинская Слобода",
                "house_number": "19"
              }
            }
        """.trimIndent()
        val nomParsed = ReverseGeocoderParser.parseNominatimJson(nominatimRaw, json)
        assertNotNull(nomParsed)
        assertEquals("Даниловский район", nomParsed!!.suburb)
        assertEquals("БЦ Омега Плаза", nomParsed.placeName)
        assertEquals("OpenStreetMap Nominatim", nomParsed.providerName)

        val partialPrimary = ParsedRussianAddress(
            country = "Россия",
            region = "Москва",
            locality = "Москва",
            street = "Ленинская Слобода",
            fullAddress = "Москва, Ленинская Слобода",
            providerName = "Komoot Photon"
        )
        val merged = ReverseGeocoderParser.mergeAddresses(partialPrimary, nomParsed)
        assertNotNull(merged)
        assertEquals("115280", merged!!.postalCode)
        assertEquals("19", merged.houseNumber)
        assertEquals("Даниловский район", merged.suburb)
        assertEquals("БЦ Омега Плаза", merged.placeName)
        assertTrue(merged.fullAddress.contains("115280"))
        assertTrue(merged.fullAddress.contains("д. 19"))
    }

    @Test
    fun geoSnapshotAccuracyQualityAndPlaceNameComment() {
        val snapHigh = GeoAddressSnapshot(
            latitude = 55.709911,
            longitude = 37.653411,
            accuracyMeters = 14f,
            capturedAtEpochMs = 1L,
            capturedAtText = "26.11.2026 15:20",
            source = GeoSource.GPS,
            fullAddress = "115280, Москва, Ленинская Слобода, д. 19",
            placeName = "БЦ Омега Плаза",
            suburb = "Даниловский район",
            altitudeMeters = 142.0,
            providerCount = 2,
            addressResolved = true
        )
        assertEquals("высокая", snapHigh.accuracyQualityLabel())
        val summary = snapHigh.formatSummaryForComment()
        assertTrue(summary.contains("Геолокация: 115280, Москва, Ленинская Слобода, д. 19"))
        assertTrue(summary.contains("Объект: БЦ Омега Плаза"))
        assertTrue(summary.contains("высокая"))

        assertEquals("средняя", snapHigh.copy(accuracyMeters = 52f).accuracyQualityLabel())
        assertEquals("приблизительная", snapHigh.copy(accuracyMeters = 180f).accuracyQualityLabel())
    }

    @Test
    fun equipmentScanParserExtractsExpectedMergesBarcodeWithOcrAndManualEntry() {
        val ticketText = "Настройка МФУ Lexmark MX622ade, S/N: 70169455511, инв. № INV-908"
        val expected = EquipmentScanParser.extractExpectedFromTicket(ticketText)
        assertNotNull(expected)
        assertTrue(expected!!.modelName.contains("Lexmark", ignoreCase = true))
        assertEquals("70169455511", expected.serialNumber)
        assertEquals("INV-908", expected.inventoryNumber)

        val barcodeOnly = EquipmentScanParser.parse(
            barcodeValues = listOf("S/N: 70169455511"),
            ocrLines = emptyList(),
            ticketText = ticketText
        )
        assertNotNull(barcodeOnly)
        val merged = EquipmentScanParser.merge(
            existing = barcodeOnly,
            barcodeValues = emptyList(),
            ocrLines = listOf("Lexmark MX622ade", "Инв. № INV-908"),
            ticketText = ticketText
        )
        assertNotNull(merged)
        assertEquals("70169455511", merged!!.serialNumber)
        assertEquals("INV-908", merged.inventoryNumber)
        assertTrue(merged.modelName.contains("Lexmark", ignoreCase = true))
        assertEquals(true, merged.matchesTicket)

        val manualWrong = EquipmentScanParser.buildManualResult(
            modelName = "Kyocera M2040dn",
            serialNumber = "bad-serial-1",
            inventoryNumber = "",
            ticketText = ticketText
        )
        assertNotNull(manualWrong)
        assertEquals("BAD-SERIAL-1", manualWrong!!.serialNumber)
        assertEquals(false, manualWrong.matchesTicket)
    }

    @Test
    fun equipmentScanParserSkipsOemStickerPartCodesAndImdaAndExtractsRealSerial() {
        // Barcode containing only OEM sticker P/N code (like QR 36S2890-004 on back label) must NOT lock as S/N
        val barcodeOnlyRejected = EquipmentScanParser.parse(
            barcodeValues = listOf("36S2890-004"),
            ocrLines = emptyList(),
            ticketText = "",
            catalogEntries = emptyList()
        )
        assertNull(barcodeOnlyRejected)
        assertEquals("36S2890-004", EquipmentScanParser.findRejectedBarcodeLabel(listOf("36S2890-004")))

        // Full sticker OCR with IMDA approval DA102160, QR P/N 36S2890-004, and actual serial CCAI18F10040T4
        val res = EquipmentScanParser.parse(
            barcodeValues = listOf("36S2890-004"),
            ocrLines = listOf(
                "Complies with",
                "IMDA Standards",
                "DA102160",
                "36S2890-004",
                "CCAI18F10040T4"
            ),
            ticketText = "Заявка Lexmark MX622ade S/N CCAI18F10040T4",
            catalogEntries = emptyList()
        )
        assertNotNull(res)
        assertEquals("CCAI18F10040T4", res!!.serialNumber)
        assertEquals(true, res.matchesTicket)
        assertTrue(res.skippedCodes.contains("36S2890-004"))
        assertTrue(res.skippedCodes.contains("DA102160"))
    }

    @Test
    fun bundledOfficialPrinterCatalogParsesSearchesAndMatchesModels() {
        val candidates = listOf(
            File("app/src/main/assets/printer_models_catalog.json"),
            File("src/main/assets/printer_models_catalog.json")
        )
        val assetFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected bundled printer_models_catalog.json", assetFile)
        val raw = assetFile!!.readText(Charsets.UTF_8)
        val entries = PrinterModelCatalogEngine.parseCatalogJson(raw, json)
        assertTrue("Expected at least 500 official models, got ${entries.size}", entries.size >= 500)

        val indexed = PrinterModelCatalogEngine.buildIndex(entries)
        val lexmarkHits = PrinterModelCatalogEngine.search(indexed, "mx622")
        assertTrue(lexmarkHits.any { it.fullName.equals("Lexmark MX622ade", ignoreCase = true) })

        val katyushaHits = PrinterModelCatalogEngine.search(indexed, "катюша 247")
        assertTrue(katyushaHits.any { it.brand == "Катюша" && it.model.contains("247") })

        val kyoceraHits = PrinterModelCatalogEngine.search(indexed, "2040", brandFilter = "Kyocera")
        assertTrue(kyoceraHits.any { it.model.contains("2040", ignoreCase = true) })

        val hpHits = PrinterModelCatalogEngine.search(indexed, "m404dn")
        assertTrue(hpHits.any { it.brand == "HP" && it.model.contains("M404dn", ignoreCase = true) })

        val zebraHits = PrinterModelCatalogEngine.search(indexed, "zd421")
        assertTrue(zebraHits.any { it.brand == "Zebra" && it.model.contains("ZD421", ignoreCase = true) })

        assertEquals(
            "Lexmark MX622ade",
            PrinterModelCatalogEngine.matchModelFromText(entries, "Меню конфигурации MX622ade версия")
        )
    }

    @Test
    fun ticketListStatusWhitelistAllowsOnlyThreeTargetStatuses() {
        assertTrue(IntraRepository.isAllowedListStatusName("Доведена до СП"))
        assertTrue(IntraRepository.isAllowedListStatusName("  доведена   до   сп "))
        assertTrue(IntraRepository.isAllowedListStatusName("Направлен СИ"))
        assertTrue(IntraRepository.isAllowedListStatusName("Эскалация Исполнителю"))
        assertTrue(IntraRepository.isAllowedListStatusName("эскалация исполнителю"))

        assertFalse(IntraRepository.isAllowedListStatusName("Принят СП"))
        assertFalse(IntraRepository.isAllowedListStatusName("Назначен СИ"))
        assertFalse(IntraRepository.isAllowedListStatusName("Принята в работу"))
        assertFalse(IntraRepository.isAllowedListStatusName("Открыта"))
        assertFalse(IntraRepository.isAllowedListStatusName("Ожидание Согаз"))
        assertFalse(IntraRepository.isAllowedListStatusName("Починил"))
        assertFalse(IntraRepository.isAllowedListStatusName("Закрыта"))
        assertFalse(IntraRepository.isAllowedListStatusName(null))

        val statuses = listOf(
            TaskStatusDto(id = 10, name = "Открыта", isFinal = false),
            TaskStatusDto(id = 11, name = "Доведена до СП", isFinal = false),
            TaskStatusDto(id = 12, name = "Направлен СИ", isFinal = false),
            TaskStatusDto(id = 13, name = "Эскалация Исполнителю", isFinal = false),
            TaskStatusDto(id = 26, name = "Починил", isFinal = false),
            TaskStatusDto(id = 30, name = "Закрыта", isFinal = true)
        )
        assertEquals(setOf(11, 12, 13), IntraRepository.findAllowedListStatusIds(statuses))
    }

    @Test
    fun equipmentScanParserRejectsBareVendorRmnAndVoltageAndNormalizesOToZero() {
        val entries = listOf(
            com.lantaservice.intra.util.PrinterCatalogEntry("HP", "LaserJet Pro M1132", "МФУ"),
            com.lantaservice.intra.util.PrinterCatalogEntry("HP", "LaserJet Pro M404dn", "Принтер")
        )
        // Screenshot 2 exact plate: bare vendor HEWLETT PACKARD + RMN BOISB0901-03 + 220-240V + CNG9D4VOG3
        val plateOnly = EquipmentScanParser.parse(
            barcodeValues = emptyList(),
            ocrLines = listOf("HEWLETT PACKARD", "BOISB0901-03", "220-240V", "CNG9D4VOG3"),
            ticketText = "Ремонт HP LaserJet Pro M1132 S/N CNG9D4V0G3",
            catalogEntries = entries
        )
        assertNotNull(plateOnly)
        assertEquals("", plateOnly!!.modelName)
        assertEquals("HP", plateOnly.detectedVendor)
        assertEquals("CNG9D4V0G3", plateOnly.serialNumber)
        assertEquals(listOf("CNG9D4V0G3"), plateOnly.candidateSerials)
        assertEquals(true, plateOnly.matchesTicket)

        // Multi-line HP sticker where concrete model is on a separate line below HEWLETT PACKARD
        val withModel = EquipmentScanParser.parse(
            barcodeValues = emptyList(),
            ocrLines = listOf("HEWLETT PACKARD", "LaserJet Pro M1132", "S/N: CNG9D4VOG3"),
            ticketText = "",
            catalogEntries = entries
        )
        assertNotNull(withModel)
        assertEquals("HP LaserJet Pro M1132", withModel!!.modelName)
        assertEquals("CNG9D4V0G3", withModel.serialNumber)
    }

    @Test
    fun reverseGeocoderParserExtractsOverpassBuildingAndEntranceAndMultiFeaturePhoton() {
        val roadNominatimRaw = """
            {
              "address": {
                "postcode": "141421",
                "country": "Россия",
                "state": "Московская область",
                "state_district": "городской округ Химки",
                "suburb": "Сходня",
                "city": "Химки",
                "road": "2-й Дачный переулок"
              }
            }
        """.trimIndent()
        val nominatimRoad = ReverseGeocoderParser.parseNominatimJson(roadNominatimRaw, json)
        assertNotNull(nominatimRoad)
        assertEquals("", nominatimRoad!!.houseNumber)

        val overpassRaw = """
            {
              "elements": [
                {
                  "type": "way",
                  "center": { "lat": 55.946400, "lon": 37.289780 },
                  "tags": {
                    "addr:housenumber": "4",
                    "addr:street": "2-й Дачный переулок"
                  }
                },
                {
                  "type": "node",
                  "lat": 55.946370,
                  "lon": 37.289740,
                  "tags": {
                    "entrance": "staircase",
                    "ref": "2",
                    "addr:flats": "37-72"
                  }
                }
              ]
            }
        """.trimIndent()
        val overpassAddr = ReverseGeocoderParser.parseOverpassBuildingAndEntrance(
            overpassRaw,
            targetLat = 55.946363,
            targetLon = 37.289728,
            json = json
        )
        assertNotNull(overpassAddr)
        assertEquals("4", overpassAddr!!.houseNumber)
        assertEquals("2 (кв. 37-72)", overpassAddr.entrance)

        val merged = ReverseGeocoderParser.mergeAddresses(nominatimRoad, overpassAddr)
        assertNotNull(merged)
        assertEquals("4", merged!!.houseNumber)
        assertEquals("2 (кв. 37-72)", merged.entrance)
        assertTrue(merged.fullAddress.contains("д. 4"))
        assertTrue(merged.fullAddress.contains("подъезд 2 (кв. 37-72)"))

        val multiPhotonRaw = """
            {
              "features": [
                { "properties": { "street": "2-й Дачный переулок", "city": "Химки" } },
                { "properties": { "street": "2-й Дачный переулок", "housenumber": "4", "city": "Химки", "postcode": "141421" } }
              ]
            }
        """.trimIndent()
        val photonAddr = ReverseGeocoderParser.parsePhotonJson(multiPhotonRaw, json)
        assertNotNull(photonAddr)
        assertEquals("4", photonAddr!!.houseNumber)
    }

    @Test
    fun serialOnlyVerificationDetectsMismatchAndFormatsAvrComment() {
        val ticketText = "Замена термоблока HP LaserJet M1132, S/N CNG9D4V0G3"

        // Matching serial with O -> 0 normalization
        val matched = EquipmentScanParser.buildManualResult(
            modelName = "",
            serialNumber = "CNG9D4VOG3",
            inventoryNumber = "",
            ticketText = ticketText,
            rawSource = "Ручной ввод"
        )
        assertNotNull(matched)
        assertEquals("CNG9D4V0G3", matched!!.serialNumber)
        assertEquals(true, matched.matchesTicket)
        assertEquals("Серийный номер совпадает с заявкой", matched.matchNote)

        // Serial-only mismatch without model still flags mismatch
        val mismatched = EquipmentScanParser.buildManualResult(
            modelName = "",
            serialNumber = "WRONG99999",
            inventoryNumber = "",
            ticketText = ticketText,
            rawSource = "Ручной ввод"
        )
        assertNotNull(mismatched)
        assertEquals(false, mismatched!!.matchesTicket)
        assertTrue(mismatched.matchNote.contains("CNG9D4V0G3"))

        // Submission comment places serial directly under AVR counter and omits "Оборудование:"
        val comment = IntraRepository.buildSubmissionComment(
            userComment = "Выполнено ТО",
            counterValue = "54120",
            equipmentScan = matched
        )
        val counterIdx = comment.indexOf("Счётчик копий (АВР): 54 120")
        val serialIdx = comment.indexOf("Серийный номер (АВР): CNG9D4V0G3 (Серийный номер совпадает с заявкой)")
        assertTrue(counterIdx >= 0)
        assertTrue(serialIdx > counterIdx)
        assertFalse(comment.contains("Оборудование:"))
    }

    @Test
    fun dimPaperSheetDetectionAndFitCenterMappingAndHonestQualityMetrics() {
        // 1. Dim paper (luma ~ 92..135) with dark printed divider lines on dark tabletop (~20)
        val w = 112
        val h = 150
        val dimLuma = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onSheet = x in 14..98 && y in 16..134
                val isPrintedDivider = onSheet && (y == 48 || y == 82 || y == 104)
                val v = when {
                    isPrintedDivider -> 42
                    onSheet -> 92 + ((y - 16) * 40 / 118)
                    else -> 18
                }
                dimLuma[y * w + x] = v.toByte()
            }
        }
        val dimQuad = DocumentGeometry.detectPaperQuadFromLuma(dimLuma, w, h)
        assertNotNull("Expected dim paper sheet to be detected cleanly", dimQuad)
        assertTrue("Top edge must not be clipped low (got ${dimQuad!!.topLeft.y})", dimQuad.topLeft.y <= 26f)
        assertTrue("Left edge must be captured cleanly (got ${dimQuad.topLeft.x})", dimQuad.topLeft.x <= 22f)

        // 2. Dim / soft-blur center frame (~106 center luma, ~90 lapVar) -> LOW_LIGHT + BLURRY (not green!)
        val qw = 80
        val qh = 100
        val dimFrame = ByteArray(qw * qh) { idx ->
            val x = idx % qw
            val y = idx / qw
            when {
                x !in 10..70 || y !in 12..88 -> 20
                (x / 3 + y / 4) % 2 == 0 -> 110
                else -> 103
            }.toByte()
        }
        val (dimMetrics, _) = FrameQualityAnalyzer.evaluateFrameQuality(dimFrame, qw, qh)
        assertEquals(LightStatus.LOW_LIGHT, dimMetrics.light)
        assertEquals(SharpnessStatus.BLURRY, dimMetrics.sharpness)
        assertFalse(dimMetrics.isOptimalToShoot)

        // 3. Sheet extending past frame edge (2+ corners on border) -> CLIPPED issue
        val clippedQuad = DocumentQuad(
            Point2D(80f, 100f),
            Point2D(1195f, 120f),
            Point2D(1196f, 1592f),
            Point2D(60f, 1594f)
        )
        val clippedReport = DocumentGeometry.evaluateQuadGeometry(clippedQuad, 1200, 1600)
        assertEquals(DocumentQualityIssue.CLIPPED, clippedReport.issue)
        assertFalse(clippedReport.canSafelyFlatten)

        // 4. FIT_CENTER mapping aligns normalized frame coordinates to letterboxed preview box
        val tl = DocumentGeometry.mapNormalizedPointToFitCenter(
            point = Point2D(0f, 0f),
            frameW = 300,
            frameH = 400,
            viewW = 600f,
            viewH = 600f
        )
        val br = DocumentGeometry.mapNormalizedPointToFitCenter(
            point = Point2D(1f, 1f),
            frameW = 300,
            frameH = 400,
            viewW = 600f,
            viewH = 600f
        )
        assertEquals(75f, tl.x, 0.5f)
        assertEquals(0f, tl.y, 0.5f)
        assertEquals(525f, br.x, 0.5f)
        assertEquals(600f, br.y, 0.5f)
    }

    @Test
    fun bottomBlankClippedSheetIsNotRejectedAndComputesProportionalA4Completion() {
        // Upper ~70% of A4 sheet touching ONLY bottom frame edge (y bottom = 1592 in 1200x1600), top/sides inside
        val topHalfSheet = DocumentQuad(
            Point2D(180f, 660f),
            Point2D(1020f, 670f),
            Point2D(1080f, 1592f),
            Point2D(120f, 1592f)
        )
        // When bottom 8% is blank white paper -> NONE + shouldCompleteBottomToA4 = true + proportional A4 layout
        val blankBottomReport = DocumentGeometry.evaluateQuadGeometry(
            quad = topHalfSheet,
            fullWidth = 1200,
            fullHeight = 1600,
            bottomEdgeInkRatio = 0.004f
        )
        assertEquals(DocumentQualityIssue.NONE, blankBottomReport.issue)
        assertTrue(blankBottomReport.canSafelyFlatten)
        assertTrue(blankBottomReport.shouldCompleteBottomToA4)

        val layout = DocumentGeometry.computeFlatA4CanvasLayout(
            quad = topHalfSheet,
            completeBottomToA4 = true
        )
        assertTrue(layout.completedBottomToA4)
        assertTrue(layout.visibleHeight < layout.canvasHeight)
        val a4Ratio = layout.canvasHeight.toFloat() / layout.canvasWidth.toFloat()
        assertEquals(1.414f, a4Ratio, 0.03f)

        // When bottom 8% still has printed ink text -> CLIPPED warning & cannot safely complete
        val inkedBottomReport = DocumentGeometry.evaluateQuadGeometry(
            quad = topHalfSheet,
            fullWidth = 1200,
            fullHeight = 1600,
            bottomEdgeInkRatio = 0.06f
        )
        assertEquals(DocumentQualityIssue.CLIPPED, inkedBottomReport.issue)
        assertFalse(inkedBottomReport.canSafelyFlatten)
    }

    @Test
    fun weightedAutoRotationNeverRotatesUprightDocumentSideways() {
        // Upright HP config page with many horizontal lines and a couple noisy vertical column fragments
        val uprightWithNoise = listOf(
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 1.2f, alnumCount = 28, boxWidth = 420, boxHeight = 24),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = -0.8f, alnumCount = 24, boxWidth = 380, boxHeight = 22),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 90f, alnumCount = 4, boxWidth = 18, boxHeight = 70),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 89f, alnumCount = 5, boxWidth = 20, boxHeight = 82)
        )
        assertEquals(0, DocumentGeometry.resolveRotationFromWeightedLines(uprightWithNoise))

        // Genuinely sideways 90-degree document (vertical boxes dominate strongly)
        val genuinelySideways = listOf(
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 90f, alnumCount = 24, boxWidth = 24, boxHeight = 360),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 89f, alnumCount = 22, boxWidth = 22, boxHeight = 340),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 91f, alnumCount = 19, boxWidth = 22, boxHeight = 310)
        )
        assertEquals(270, DocumentGeometry.resolveRotationFromWeightedLines(genuinelySideways))
    }

    @Test
    fun hpRussianConfigPageExtractsSerialNumberAndTotalPrintedPagesCounter() {
        // Exact HP M1212nf Cyrillic config page lines (must pick 2320 not 155, and CNG9D4V0G3 not QJ551EF/31237/20201023)
        val cyrillicLines = listOf(
            "HP LaserJet Professional M1212nf MFP",
            "Номер форматера: QJ551EF",
            "Серийный номер продукта: CNG9D4VOG3",
            "Идентиф. номер службы: 31237",
            "Код даты микропрог.: 20201023",
            "Отпечатано ч/б стр. с копии = 155",
            "Всего напечатано страниц = 2320",
            "Всего замытий на входе = 1"
        )
        assertEquals("CNG9D4V0G3", EquipmentScanParser.extractLabeledSerialFromOcrLines(cyrillicLines))
        val cyrCounter = com.lantaservice.intra.util.AvrCounterParser.extractCounterFromLines(cyrillicLines)
        assertEquals(com.lantaservice.intra.util.CounterConfidence.HIGH, cyrCounter.confidence)
        assertEquals("2320", cyrCounter.value)

        // Latin-OCR homoglyph-garbled Cyrillic config lines
        val latinGarbledLines = listOf(
            "CepuuHbIu HoMep npogykTa CNG9D4VOG3",
            "OTne4aTaHo 4/6 cTp. c konuu = 155",
            "Bcero HaneyataHo cTpaHML = 2320"
        )
        assertEquals("CNG9D4V0G3", EquipmentScanParser.extractLabeledSerialFromOcrLines(latinGarbledLines))
        val garbledCounter = com.lantaservice.intra.util.AvrCounterParser.extractCounterFromLines(latinGarbledLines)
        assertEquals(com.lantaservice.intra.util.CounterConfidence.HIGH, garbledCounter.confidence)
        assertEquals("2320", garbledCounter.value)
    }

    @Test
    fun shadowedBlankBottomAndGrazedRightMarginStillFlattensAndExtrapolatesCorner() {
        val w = 240
        val h = 320
        val luma = ByteArray(w * h) { 35.toByte() }

        // Sheet leaning so topRight is inside (212, 84) and bottomRight exits x=239 near y=253
        val tlX = 34f
        val topY = 68f
        val trX = 212f
        val trY = 84f
        val leftSlope = -0.06f
        val rightSlope = 0.16f

        for (y in topY.toInt() until h) {
            val minLeft = tlX + (y - topY) * leftSlope
            val maxRight = trX + (y - trY) * rightSlope

            // Illumination fades smoothly from 240 at top to 175 at shaded bottom
            val vFrac = (y - topY) / (h - topY)
            val paperShade = (240f - 65f * vFrac).toInt().coerceIn(170, 245)

            for (x in 0 until w) {
                val localTopAtX = topY + (trY - topY) * ((x - tlX) / maxOf(1f, trX - tlX))
                if (x >= minLeft && x <= maxRight && y >= localTopAtX) {
                    val uFrac = (x - minLeft) / maxOf(1f, maxRight - minLeft)
                    val isUpperPrintedLine = vFrac in 0.15f..0.68f &&
                        uFrac in 0.10f..0.85f &&
                        ((y % 18) in 3..5)
                    luma[y * w + x] = (if (isUpperPrintedLine) 55 else paperShade).toByte()
                }
            }
        }

        val quad = DocumentGeometry.detectPaperQuadFromLuma(luma, w, h)
        assertNotNull(quad)
        // Extrapolated bottomRight should recover virtual X past right border (> 240)
        assertTrue("Expected extrapolated bottomRight.x > 240, got ${quad!!.bottomRight.x}", quad.bottomRight.x > 240f)

        val lumaInt = IntArray(luma.size) { i -> luma[i].toInt() and 0xFF }
        val profile = DocumentGeometry.computeEdgeInkProfileFromLuma(lumaInt, w, h, quad)
        // Shaded bottom strip must NOT be misclassified as text compared against top lamp brightness
        assertTrue("Expected low local bottom ink ratio < 0.018, got ${profile.bottom}", profile.bottom < 0.018f)

        val fullQuad = quad.scaled(scaleX = 4f, scaleY = 4f)
        val report = DocumentGeometry.evaluateQuadGeometry(
            quad = fullQuad,
            fullWidth = w * 4,
            fullHeight = h * 4,
            edgeInkProfile = profile
        )
        assertTrue(report.canSafelyFlatten)
        assertEquals(com.lantaservice.intra.util.DocumentQualityIssue.NONE, report.issue)
        assertTrue(report.shouldCompleteBottomToA4)
    }

    @Test
    fun fineDeskewAngleStraightensSlightlyTiltedHorizontalLines() {
        val tiltedRight = listOf(
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 5.2f, alnumCount = 34, boxWidth = 480, boxHeight = 26),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 5.0f, alnumCount = 26, boxWidth = 420, boxHeight = 24),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 5.4f, alnumCount = 18, boxWidth = 360, boxHeight = 24),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = -89.0f, alnumCount = 6, boxWidth = 22, boxHeight = 150)
        )
        val corrCw = DocumentGeometry.computeFineDeskewAngleDeg(tiltedRight)
        assertTrue("Expected ~-5.2 deg counter-rotation, got $corrCw", kotlin.math.abs(corrCw - (-5.2f)) < 0.25f)

        val tinyNoise = listOf(
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = 0.35f, alnumCount = 30, boxWidth = 420, boxHeight = 24)
        )
        assertEquals(0f, DocumentGeometry.computeFineDeskewAngleDeg(tinyNoise), 0.001f)

        val tiltedLeft = listOf(
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = -3.8f, alnumCount = 28, boxWidth = 400, boxHeight = 24),
            com.lantaservice.intra.util.TextLineOrientation(angleDeg = -3.6f, alnumCount = 22, boxWidth = 380, boxHeight = 24)
        )
        val corrCcw = DocumentGeometry.computeFineDeskewAngleDeg(tiltedLeft)
        assertTrue("Expected ~+3.8 deg rotation, got $corrCcw", kotlin.math.abs(corrCcw - 3.8f) < 0.25f)
    }

    @Test
    fun frameQualityAnalyzerRejectsNoisyBlurryFrameAndRequiresCrispInteriorEdges() {
        val w = 160
        val h = 120
        val paperRoi = com.lantaservice.intra.util.NormalizedRect(0.15f, 0.15f, 0.85f, 0.85f)

        // 1. Dim blurry frame with simulated high-ISO sensor grain (+-8 luma noise, wide soft bars)
        val noisyBlurry = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Wide gradual ramp (out-of-focus blur) + deterministic checkerboard ISO grain
                val wave = 136 + (16.0 * kotlin.math.sin(x / 6.0) * kotlin.math.cos(y / 6.0)).toInt()
                val grain = if ((x + y) % 2 == 0) 6 else -6
                noisyBlurry[y * w + x] = (wave + grain).coerceIn(0, 255).toByte()
            }
        }
        val (blurMetrics, _) = FrameQualityAnalyzer.evaluateFrameQuality(
            luma = noisyBlurry,
            width = w,
            height = h,
            paperRoiNormalized = paperRoi
        )
        assertEquals(LightStatus.LOW_LIGHT, blurMetrics.light)
        assertFalse("Blurry noisy frame must NOT be reported as SharpnessStatus.OK", blurMetrics.sharpness == SharpnessStatus.OK)
        assertFalse(blurMetrics.isOptimalToShoot)

        // 2. Crisp well-lit document frame with sharp 1px text edges
        val sharpPaper = ByteArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val onTextBar = x in 32..128 && (y % 12) in 2..4 && (x % 6) < 4
            (if (onTextBar) 45 else 218).toByte()
        }
        val (sharpMetrics, steadySample) = FrameQualityAnalyzer.evaluateFrameQuality(
            luma = sharpPaper,
            width = w,
            height = h,
            paperRoiNormalized = paperRoi
        )
        assertEquals(LightStatus.OK, sharpMetrics.light)
        assertEquals(SharpnessStatus.OK, sharpMetrics.sharpness)
        assertEquals(StabilityStatus.STEADY, sharpMetrics.stability)
        assertTrue(sharpMetrics.isOptimalToShoot)

        // 3. Mild AE exposure drift (+8 luma) on stationary phone must stay STEADY
        val aeDrifted = ByteArray(w * h) { idx ->
            val v = (sharpPaper[idx].toInt() and 0xFF) + 8
            v.coerceIn(0, 255).toByte()
        }
        val (driftMetrics, _) = FrameQualityAnalyzer.evaluateFrameQuality(
            luma = aeDrifted,
            width = w,
            height = h,
            prevSample = steadySample,
            paperRoiNormalized = paperRoi
        )
        assertEquals(StabilityStatus.STEADY, driftMetrics.stability)
    }

    @Test
    fun avrCounterParserNeverStealsNumberFromNextLabeledRowAndReadsMultiVendorLabels() {
        // Blank value on Total row must NOT steal "= 12" from the next labeled sub-counter row
        val missingTotalValueLines = listOf(
            "Всего напечатано страниц",
            "Отсканир. стр. АПД на хост = 12",
            "Факс. стр. отпечатано = 0"
        )
        val missingRes = AvrCounterParser.extractCounterFromLines(missingTotalValueLines)
        assertEquals(CounterConfidence.NONE, missingRes.confidence)
        assertEquals(null, missingRes.value)

        // True tabular next-line pure value MUST still be accepted
        val wrappedTotalLines = listOf(
            "Всего напечатано страниц",
            "= 2320",
            "Отсканир. стр. АПД на хост = 12"
        )
        val wrappedRes = AvrCounterParser.extractCounterFromLines(wrappedTotalLines)
        assertEquals(CounterConfidence.HIGH, wrappedRes.confidence)
        assertEquals("2320", wrappedRes.value)

        // Multi-vendor total labels
        assertEquals("84120", AvrCounterParser.extractCounterFromLines(listOf("Total Billing Impressions: 84 120")).value)
        assertEquals("49102", AvrCounterParser.extractCounterFromLines(listOf("Life Count 49102")).value)
        assertEquals("19405", AvrCounterParser.extractCounterFromLines(listOf("Общий счетчик: 19 405")).value)
    }

    @Test
    fun equipmentScanParserExtractsGarbledCyrillicAndMultiVendorSerialNumbers() {
        // Corrupted Cyrillic homoglyphs + O->0 normalization
        assertEquals(
            "CNG9D4V0G3",
            EquipmentScanParser.extractLabeledSerialFromOcrLines(
                listOf("Серииный Номep пр0дукта CNG9D4VOG3")
            )
        )

        // Formatter metadata row adjacent to real serial row must not block extraction
        assertEquals(
            "CNG9D4V0G3",
            EquipmentScanParser.extractLabeledSerialFromOcrLines(
                listOf(
                    "Номер форматера: QJ551EF",
                    "Серийный номер продукта: CNG9D4V0G3",
                    "Идентиф. номер службы: 31237"
                )
            )
        )

        // Config-sheet fallback when serial label blurred out completely
        assertEquals(
            "CNG9D4V0G3",
            EquipmentScanParser.extractLabeledSerialFromOcrLines(
                listOf(
                    "HP LaserJet Professional M1212nf MFP",
                    "Самопроверка / Конфигурация",
                    "Сведения о продукте",
                    "Номер форматера QJ551EF",
                    "CNG9D4VOG3"
                )
            )
        )

        // English vendor labels
        assertEquals(
            "JPBRC41082",
            EquipmentScanParser.extractLabeledSerialFromOcrLines(listOf("Engine Serial No: JPBRC41082"))
        )
        assertEquals(
            "VN8K201944",
            EquipmentScanParser.extractLabeledSerialFromOcrLines(listOf("Product Serial: VN8K201944"))
        )
    }

    @Test
    fun rowClusteringRespectsTightTablePitchAndMapsMatchedCounterRoi() {
        val boxes = listOf(
            // Left column (x 80..450)
            com.lantaservice.intra.util.OcrBoxLine(80, 180, 260, 192, "Номер форматера:"),
            com.lantaservice.intra.util.OcrBoxLine(310, 181, 410, 192, "QJ551EF"),
            com.lantaservice.intra.util.OcrBoxLine(80, 197, 290, 209, "Серийный номер продукта:"),
            com.lantaservice.intra.util.OcrBoxLine(310, 197, 430, 209, "CNG9D4VOG3"),
            com.lantaservice.intra.util.OcrBoxLine(80, 214, 280, 226, "Идентиф. номер службы:"),
            com.lantaservice.intra.util.OcrBoxLine(310, 214, 380, 226, "31237"),
            // Right column (x 540..920) with tight 16px pitch (12px tall boxes)
            com.lantaservice.intra.util.OcrBoxLine(540, 180, 760, 192, "Отпечатано ч/б стр. с копии"),
            com.lantaservice.intra.util.OcrBoxLine(840, 180, 910, 192, "= 155"),
            com.lantaservice.intra.util.OcrBoxLine(540, 196, 770, 208, "Всего напечатано страниц"),
            com.lantaservice.intra.util.OcrBoxLine(840, 196, 920, 208, "= 2320"),
            com.lantaservice.intra.util.OcrBoxLine(540, 212, 770, 224, "Отсканир. стр. АПД на хост"),
            com.lantaservice.intra.util.OcrBoxLine(840, 212, 900, 224, "= 12")
        )

        val res = com.lantaservice.intra.util.AvrCounterRecognizer.extractDocumentFieldsFromBoxedLines(
            items = boxes,
            imageWidth = 1000,
            imageHeight = 1400
        )
        assertEquals(CounterConfidence.HIGH, res.counter.confidence)
        assertEquals("2320", res.counter.value)
        assertEquals("CNG9D4V0G3", res.serialNumber)

        val roi = res.counterBoxNormalized
        assertNotNull("Expected normalized ROI around matched counter row", roi)
        // Must point directly to the right-column counter row (~y=196..208 on 1400 -> ~0.13..0.16), NOT bottom journal!
        assertTrue("Expected left > 0.50, got ${roi!!.left}", roi.left > 0.50f)
        assertTrue("Expected top in upper table (< 0.20), got ${roi.top}", roi.top < 0.20f)
        assertTrue("Expected bottom in upper table (< 0.25), got ${roi.bottom}", roi.bottom < 0.25f)
    }

    @Test
    fun shiftedValueColumnRestores2320InsteadOf12AndMonotonicityRejectsSmallerTotal() {
        // Direct parser monotonicity guard: Total cannot be 12 when adjacent printed copy sub-counter is 155
        val impossibleShiftLines = listOf(
            "Отпечатано ч/б стр. с копии = 155",
            "Всего напечатано страниц = 12",
            "Отсканир. стр. АПД на хост = 655"
        )
        val rejected = AvrCounterParser.extractCounterFromLines(impossibleShiftLines)
        assertEquals(CounterConfidence.NONE, rejected.confidence)

        // Realistic geometric 10px upward shift on 16px-pitched right value stack:
        // Raw Y distance puts "= 12" (cy=206) closer to "Всего напечатано страниц" (cy=200) than "= 2320" (cy=190)!
        val shiftedBoxes = listOf(
            com.lantaservice.intra.util.OcrBoxLine(540, 132, 740, 144, "Количество страниц"),
            com.lantaservice.intra.util.OcrBoxLine(540, 146, 770, 158, "Отсканир. стр. АПД для копир."),
            com.lantaservice.intra.util.OcrBoxLine(845, 136, 900, 148, "= 1"),
            com.lantaservice.intra.util.OcrBoxLine(540, 162, 780, 174, "Отсканир. стр. сканера для копир."),
            com.lantaservice.intra.util.OcrBoxLine(845, 152, 910, 164, "= 154"),
            com.lantaservice.intra.util.OcrBoxLine(540, 178, 775, 190, "Отпечатано ч/б стр. с копии"),
            com.lantaservice.intra.util.OcrBoxLine(845, 168, 910, 180, "= 155"),
            com.lantaservice.intra.util.OcrBoxLine(540, 194, 775, 206, "Всего напечатано страниц"),
            com.lantaservice.intra.util.OcrBoxLine(845, 184, 920, 196, "= 2320"),
            com.lantaservice.intra.util.OcrBoxLine(540, 210, 775, 222, "Отсканир. стр. АПД на хост"),
            com.lantaservice.intra.util.OcrBoxLine(845, 200, 905, 212, "= 12"),
            com.lantaservice.intra.util.OcrBoxLine(540, 226, 775, 238, "Отсканир. стр. сканера на хост"),
            com.lantaservice.intra.util.OcrBoxLine(845, 216, 910, 228, "= 655")
        )
        val res = com.lantaservice.intra.util.AvrCounterRecognizer.extractDocumentFieldsFromBoxedLines(
            items = shiftedBoxes,
            imageWidth = 1000,
            imageHeight = 1400
        )
        assertEquals(CounterConfidence.HIGH, res.counter.confidence)
        assertEquals("2320", res.counter.value)
    }

    @Test
    fun extractTicketAddressParsesExplicitAndDescriptionAddresses() {
        val fromDesc = DateAndTextFormatter.extractTicketAddress(
            title = "Замена термоплёнки HP M1212nf",
            description = "Клиент: ООО Альфа\nАдрес объекта: г. Химки, 2-й Дачный пер., д. 5\nКонтакт: Иван",
            explicitAddress = null
        )
        assertEquals("г. Химки, 2-й Дачный пер., д. 5", fromDesc)

        val fromStreetCue = DateAndTextFormatter.extractTicketAddress(
            title = "Ремонт МФУ",
            description = "Не берёт бумагу.\nМосква, ул. Тверская, д. 12, каб. 4",
            explicitAddress = null
        )
        assertEquals("Москва, ул. Тверская, д. 12, каб. 4", fromStreetCue)

        val explicitWins = DateAndTextFormatter.extractTicketAddress(
            title = "Ремонт",
            description = "Адрес: старое описание",
            explicitAddress = "141421, г. Химки, ул. Ленина, 10"
        )
        assertEquals("141421, г. Химки, ул. Ленина, 10", explicitWins)

        val emptyPlaceholder = DateAndTextFormatter.extractTicketAddress(
            title = "Ремонт принтера",
            description = "Адрес: Не указан",
            explicitAddress = null
        )
        assertEquals("", emptyPlaceholder)
    }
}
