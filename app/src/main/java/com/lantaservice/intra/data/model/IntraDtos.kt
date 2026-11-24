package com.lantaservice.intra.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Serializable
data class CurrentUserDto(
    @SerialName("Id") val id: Int,
    @SerialName("Login") val login: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Email") val email: String? = null,
    @SerialName("Phone") val phone: String? = null,
    @SerialName("MobilePhone") val mobilePhone: String? = null,
    @SerialName("Position") val position: String? = null,
    @SerialName("CompanyId") val companyId: Int? = null,
    @SerialName("RoleId") val roleId: Int? = null,
    @SerialName("RoleType") val roleType: Int? = null,
    @SerialName("IsArchive") val isArchive: Boolean? = null,
    @SerialName("DefaultTaskFilterId") val defaultTaskFilterId: Int? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("UtcOffset") val utcOffset: String? = null
)

@Serializable
data class TaskStatusDto(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String = "",
    @SerialName("Description") val description: String? = null,
    @SerialName("Image16Url") val image16Url: String? = null,
    @SerialName("Image24Url") val image24Url: String? = null,
    @SerialName("IsInitial") val isInitial: Boolean = false,
    @SerialName("IsCommentRequired") val isCommentRequired: Boolean = false,
    @SerialName("IsFinal") val isFinal: Boolean = false,
    @SerialName("IsFixed") val isFixed: Boolean = false,
    @SerialName("IsDeadlineAccountingPaused") val isDeadlineAccountingPaused: Boolean = false,
    @SerialName("IsConcurrence") val isConcurrence: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false
)

@Serializable
data class TaskPriorityDto(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String = "",
    @SerialName("Description") val description: String? = null,
    @SerialName("Image16Url") val image16Url: String? = null
)

@Serializable
data class ServiceDto(
    @SerialName("Id") val id: Int,
    @SerialName("Code") val code: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("Description") val description: String? = null,
    @SerialName("IsArchive") val isArchive: Boolean = false,
    @SerialName("IsPublic") val isPublic: Boolean = false
)

@Serializable
data class PaginatorDto(
    @SerialName("Count") val count: Int = 0,
    @SerialName("Page") val page: Int = 1,
    @SerialName("PageCount") val pageCount: Int = 1,
    @SerialName("PageSize") val pageSize: Int = 25,
    @SerialName("CountOnPage") val countOnPage: Int = 0
)

/**
 * IntraService docs show Paginator as either a JSON object (p. 9) or a 1-element array (p. 16).
 */
object PaginatorSerializer : KSerializer<PaginatorDto?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PaginatorDtoNullable")

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: PaginatorDto?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeSerializableValue(PaginatorDto.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): PaginatorDto? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(PaginatorDto.serializer())
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonObject -> jsonDecoder.json.decodeFromJsonElement(PaginatorDto.serializer(), element)
            is JsonArray -> {
                val list = jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(PaginatorDto.serializer()),
                    element
                )
                list.firstOrNull()
            }
            else -> null
        }
    }
}

@Serializable
data class TaskSummaryDto(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("StatusId") val statusId: Int? = null,
    @SerialName("StatusName") val statusName: String? = null,
    @SerialName("StatusIsFixed") val statusIsFixed: Boolean? = null,
    @SerialName("StatusIsFinal") val statusIsFinal: Boolean? = null,
    @SerialName("PriorityId") val priorityId: Int? = null,
    @SerialName("PriorityName") val priorityName: String? = null,
    @SerialName("ServiceId") val serviceId: Int? = null,
    @SerialName("ServiceName") val serviceName: String? = null,
    @SerialName("Created") val created: String? = null,
    @SerialName("Changed") val changed: String? = null,
    @SerialName("Deadline") val deadline: String? = null,
    @SerialName("Closed") val closed: String? = null,
    @SerialName("Creator") val creator: String? = null,
    @SerialName("CreatorId") val creatorId: Int? = null,
    @SerialName("CreatorPhone") val creatorPhone: String? = null,
    @SerialName("CreatorMobilePhone") val creatorMobilePhone: String? = null,
    @SerialName("ExecutorIds") val executorIds: String? = null,
    @SerialName("Executors") val executors: String? = null,
    @SerialName("FileIds") val fileIds: String? = null,
    @SerialName("FileNames") val fileNames: String? = null,
    @SerialName("Files") val files: String? = null,
    @SerialName("ResolutionOverdue") val resolutionOverdue: Boolean? = null,
    @SerialName("ReactionOverdue") val reactionOverdue: Boolean? = null,
    @SerialName("Categories") val categories: String? = null,
    @SerialName("Assets") val assets: String? = null
)

@Serializable
data class TaskListResponseDto(
    @SerialName("Tasks") val tasks: List<TaskSummaryDto> = emptyList(),
    @SerialName("Statuses") val statuses: List<TaskStatusDto> = emptyList(),
    @SerialName("Priorities") val priorities: List<TaskPriorityDto> = emptyList(),
    @SerialName("Services") val services: List<ServiceDto> = emptyList(),
    @SerialName("Paginator")
    @Serializable(with = PaginatorSerializer::class)
    val paginator: PaginatorDto? = null
)

@Serializable
data class TaskStatusSimpleViewDto(
    @SerialName("Id") val id: Int,
    @SerialName("Name") val name: String = "",
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsConcurrence") val isConcurrence: Boolean = false
)

@Serializable
data class UserTaskRightsDto(
    @SerialName("Status") val status: Int = 0,
    @SerialName("Files") val files: Int = 0,
    @SerialName("Comment") val comment: Int = 0,
    @SerialName("ToStatuses") val toStatuses: List<TaskStatusSimpleViewDto> = emptyList()
)

@Serializable
data class TaskFormDto(
    @SerialName("Task") val task: TaskSummaryDto? = null,
    @SerialName("Statuses") val statuses: List<TaskStatusDto> = emptyList(),
    @SerialName("Priorities") val priorities: List<TaskPriorityDto> = emptyList(),
    @SerialName("Services") val services: List<ServiceDto> = emptyList(),
    @SerialName("UserTaskRights") val userTaskRights: UserTaskRightsDto? = null
)

@Serializable
data class TaskLifetimeDto(
    @SerialName("Date") val date: String? = null,
    @SerialName("EditorId") val editorId: Int? = null,
    @SerialName("Editor") val editor: String? = null,
    @SerialName("StatusId") val statusId: Int? = null,
    @SerialName("Comments") val comments: String? = null,
    @SerialName("IsPublic") val isPublic: Boolean? = null,
    @SerialName("Files") val files: String? = null,
    @SerialName("AddedFiles") val addedFiles: String? = null,
    @SerialName("RemovedFiles") val removedFiles: String? = null
)

@Serializable
data class TaskLifetimeResponseDto(
    @SerialName("TaskLifetimes") val taskLifetimes: List<TaskLifetimeDto> = emptyList(),
    @SerialName("Statuses") val statuses: List<TaskStatusDto> = emptyList(),
    @SerialName("Paginator")
    @Serializable(with = PaginatorSerializer::class)
    val paginator: PaginatorDto? = null
)

@Serializable
data class UpdateTaskRequestDto(
    @SerialName("StatusId") val statusId: Int? = null,
    @SerialName("FileTokens") val fileTokens: String? = null,
    @SerialName("Comment") val comment: String? = null
)

@Serializable
data class IntraErrorDto(
    @SerialName("Message") val message: String? = null,
    @SerialName("MessageDetail") val messageDetail: String? = null
)

data class UserSession(
    val baseUrl: String,
    val login: String,
    val password: String,
    val authCookieHeader: String? = null,
    val userId: Int,
    val displayName: String,
    val email: String?,
    val phone: String?,
    val position: String?,
    val roleType: Int?
)

data class TaskAttachment(
    val id: String,
    val name: String,
    val isImage: Boolean,
    val url: String
)

data class TicketItem(
    val id: Int,
    val name: String,
    val descriptionPreview: String,
    val statusId: Int?,
    val statusName: String,
    val priorityId: Int?,
    val priorityName: String,
    val serviceName: String,
    val creatorName: String,
    val createdText: String,
    val changedText: String,
    val changedRaw: String,
    val deadlineText: String,
    val isOverdue: Boolean,
    val attachmentCount: Int,
    val hasHadRepairedStatus: Boolean,
    val isLifetimeChecked: Boolean
)

data class TicketLifetimeEvent(
    val dateText: String,
    val editorName: String,
    val statusId: Int?,
    val statusName: String,
    val comment: String
)

data class TicketDetail(
    val id: Int,
    val name: String,
    val description: String,
    val statusId: Int?,
    val statusName: String,
    val priorityName: String,
    val serviceName: String,
    val creatorName: String,
    val creatorPhone: String?,
    val executors: String,
    val createdText: String,
    val changedText: String,
    val deadlineText: String,
    val isOverdue: Boolean,
    val isCurrentlyRepaired: Boolean,
    val hasHadRepairedStatus: Boolean,
    val canSetRepaired: Boolean,
    val repairedStatusId: Int?,
    val repairedStatusName: String,
    val isCommentRequiredForRepaired: Boolean,
    val repairedUnavailableReason: String?,
    val canAttachFiles: Boolean,
    val canComment: Boolean,
    val attachments: List<TaskAttachment>,
    val history: List<TicketLifetimeEvent>
)

data class PendingPhoto(
    val id: String,
    val file: File,
    val nameWithoutExt: String,
    val sizeBytes: Long,
    val isEnhanced: Boolean = true,
    val isBlurryWarning: Boolean = false,
    val sharpnessScore: Double = 0.0
)

data class TicketSaveResult(
    val movedToRepairedTab: Boolean,
    val attachedCount: Int,
    val statusChanged: Boolean
)

/**
 * Extracts FileTokens robustly from IntraService TaskFile JSON response.
 */
fun extractFileTokens(element: JsonElement): String {
    if (element is JsonObject) {
        val tokenValue = element["FileTokens"]
            ?: element["fileTokens"]
            ?: element.entries.firstOrNull { it.key.equals("filetokens", ignoreCase = true) }?.value
        if (tokenValue != null && tokenValue !is JsonNull) {
            return tokenValue.jsonPrimitive.content.trim()
        }
    }
    return ""
}
