package com.lantaservice.intra.data.network

import com.lantaservice.intra.data.model.CurrentUserDto
import com.lantaservice.intra.data.model.TaskLifetimeResponseDto
import com.lantaservice.intra.data.model.TaskListResponseDto
import com.lantaservice.intra.data.model.TaskStatusDto
import com.lantaservice.intra.data.model.UpdateTaskRequestDto
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit contract for IntraService REST API v4.47+.
 */
interface IntraApiService {

    @GET("api/user")
    suspend fun getCurrentUserInfo(
        @Query("getcurrentuserinfo") getCurrentUserInfo: Boolean = true,
        @Header("Authorization") explicitAuthHeader: String? = null
    ): Response<CurrentUserDto>

    @GET("api/taskstatus")
    suspend fun getStatuses(): Response<List<TaskStatusDto>>

    @GET("api/task")
    suspend fun getTasks(
        @Query("ExecutorIds") executorIds: String,
        @Query("StatusIds") statusIds: String? = null,
        @Query("include") include: String = "status,priority,service",
        @Query("sort") sort: String = "Changed desc",
        @Query("pagesize") pageSize: Int = 200,
        @Query("page") page: Int = 1
    ): Response<TaskListResponseDto>

    @GET("api/task/{taskId}")
    suspend fun getTaskDetail(
        @Path("taskId") taskId: Int,
        @Query("include") include: String = "status,priority,service,usertaskrights"
    ): Response<JsonElement>

    @GET("api/tasklifetime")
    suspend fun getTaskLifetime(
        @Query("taskid") taskId: Int,
        @Query("include") include: String? = null,
        @Query("lastcommentsontop") lastCommentsOnTop: Boolean? = null,
        @Query("pagesize") pageSize: Int = 200,
        @Query("page") page: Int = 1
    ): Response<TaskLifetimeResponseDto>

    @Multipart
    @POST("api/TaskFile")
    suspend fun uploadTaskFiles(
        @Part parts: List<MultipartBody.Part>
    ): Response<ResponseBody>

    @PUT("api/task/{taskId}")
    suspend fun updateTask(
        @Path("taskId") taskId: Int,
        @Body body: UpdateTaskRequestDto
    ): Response<ResponseBody>

    @GET("api/taskfile/{fileId}")
    suspend fun downloadTaskFile(
        @Path("fileId") fileId: String
    ): Response<ResponseBody>
}
