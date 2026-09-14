package io.relayfs.android.core.provider.pcloud.api

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface PCloudApiService {

    @GET("userinfo")
    suspend fun getUserInfo(): Response<PCloudUserInfoResponse>

    @GET("listfolder")
    suspend fun listFolder(
        @Query("folderid") folderId: Long,
        @Query("recursive") recursive: Int = 0
    ): Response<PCloudFolderResponse>

    @GET("diff")
    suspend fun getDiff(
        @Query("diffid") diffId: Long? = null,
        @Query("limit") limit: Int = 500
    ): Response<PCloudDiffResponse>

    @GET("getfilelink")
    suspend fun getFileLink(
        @Query("fileid") fileId: Long
    ): Response<PCloudFileLinkResponse>

    @POST("createfolder")
    suspend fun createFolder(
        @Query("folderid") parentFolderId: Long,
        @Query("name") name: String
    ): Response<PCloudFolderResponse>

    @POST("renamefile")
    suspend fun renameFile(
        @Query("fileid") fileId: Long,
        @Query("tofolderid") toFolderId: Long? = null,
        @Query("toname") toName: String
    ): Response<PCloudFolderResponse>

    @POST("renamefolder")
    suspend fun renameFolder(
        @Query("folderid") folderId: Long,
        @Query("tofolderid") toFolderId: Long? = null,
        @Query("toname") toName: String
    ): Response<PCloudFolderResponse>

    @POST("deletefile")
    suspend fun deleteFile(
        @Query("fileid") fileId: Long
    ): Response<PCloudBaseResponse>

    @POST("deletefolderrecursive")
    suspend fun deleteFolderRecursive(
        @Query("folderid") folderId: Long
    ): Response<PCloudBaseResponse>

    @POST("upload_create")
    suspend fun uploadCreate(): Response<PCloudUploadCreateResponse>

    @POST("upload_save")
    suspend fun uploadSave(
        @Query("uploadid") uploadId: Long,
        @Query("folderid") folderId: Long,
        @Query("name") name: String
    ): Response<PCloudUploadSaveResponse>

    @FormUrlEncoded
    @POST("oauth2_token")
    suspend fun exchangeToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String = "",
        @Field("code") code: String,
        @Field("code_verifier") codeVerifier: String
    ): Response<PCloudTokenResponse>
}

