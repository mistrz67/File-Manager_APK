package org.fossify.filemanager.network.core

/** Network file protocols supported by the built-in network drive client. */
enum class Protocol(val id: String, val defaultPort: Int, val urlScheme: String) {
    SMB("smb", 445, "smb"),
    SFTP("sftp", 22, "sftp"),
    FTP("ftp", 21, "ftp"),

    /** FTP with explicit TLS (AUTH TLS on the regular FTP port). */
    FTPES("ftpes", 21, "ftpes"),

    /** FTP with implicit TLS (TLS from the first byte, usually port 990). */
    FTPS("ftps", 990, "ftps");

    val isFtpFamily: Boolean get() = this == FTP || this == FTPES || this == FTPS

    val usesTls: Boolean get() = this == FTPES || this == FTPS

    val supportsPrivateKey: Boolean get() = this == SFTP

    companion object {
        fun fromId(id: String?): Protocol? = values().firstOrNull { it.id == id }
    }
}

enum class AuthMethod(val id: String) {
    PASSWORD("password"),
    PRIVATE_KEY("key"),
    ANONYMOUS("anonymous");

    companion object {
        fun fromId(id: String?): AuthMethod? = values().firstOrNull { it.id == id }
    }
}
