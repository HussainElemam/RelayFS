package io.relayfs.android.core.provider.pcloud.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PCloudBaseResponse(
    val result: Int,
    val error: String? = null
)

@Serializable
data class PCloudFolderResponse(
    val result: Int,
    val error: String? = null,
    val metadata: PCloudItemMetadata? = null
)

@Serializable
data class PCloudItemMetadata(
    val id: String? = null,
    val fileid: Long? = null,
    val folderid: Long? = null,
    val name: String = "",
    val path: String? = null,
    val isfolder: Boolean = false,
    val isdeleted: Boolean? = null,
    val size: Long = -1L,
    val modified: String? = null,
    @Serializable(with = RemoteHashSerializer::class)
    val hash: String? = null,
    val contenttype: String? = null,
    val parentfolderid: Long? = null,
    val contents: List<PCloudItemMetadata>? = null
) {
    /**
     * Normalized remote ID string: prefixed with "d" for directories and "f" for files.
     */
    val canonicalId: String
        get() = if (isfolder) {
            "d${folderid ?: 0}"
        } else {
            "f${fileid ?: 0}"
        }
}

@Serializable
data class PCloudDiffResponse(
    val result: Int,
    val error: String? = null,
    val diffid: Long = 0L,
    val entries: List<PCloudDiffEntry> = emptyList()
)

@Serializable
data class PCloudDiffEntry(
    val event: String = "", // "createfile", "modifyfile", "deletefile", "createfolder", "deletefolder"
    val time: String? = null,
    val diffid: Long? = null,
    val metadata: PCloudItemMetadata? = null
)

@Serializable
data class PCloudFileLinkResponse(
    val result: Int,
    val error: String? = null,
    val hosts: List<String> = emptyList(),
    val path: String = "",
    val expires: String? = null
) {
    val downloadUrl: String?
        get() = if (hosts.isNotEmpty() && path.isNotBlank()) {
            val host = hosts[0]
            val fullPath = if (path.startsWith("/")) path else "/$path"
            if (host.startsWith("http://") || host.startsWith("https://")) {
                "$host$fullPath"
            } else {
                "https://$host$fullPath"
            }
        } else null
}

@Serializable
data class PCloudUploadCreateResponse(
    val result: Int,
    val error: String? = null,
    val uploadid: Long = 0L
)

@Serializable
data class PCloudUploadSaveResponse(
    val result: Int,
    val error: String? = null,
    val metadata: List<PCloudItemMetadata> = emptyList()
)

@Serializable
data class PCloudUserInfoResponse(
    val result: Int,
    val error: String? = null,
    val email: String = "",
    val emailverified: Boolean = false,
    val userid: Long = 0L,
    val usedquota: Long = 0L,
    val quota: Long = 0L
)

@Serializable
data class PCloudTokenResponse(
    @SerialName("access_token")
    val accessToken: String = "",
    @SerialName("token_type")
    val tokenType: String = "bearer",
    val userid: Long = 0L,
    val locationid: Int = 1,
    val result: Int,
    val error: String? = null
)

