package com.lightningkite.lightningserver.filescanner

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.Platform
import xyz.capybara.clamav.commands.scan.result.ScanResult
import java.io.InputStream

class ClamAvFileScanner(
    val get: () -> ClamavClient
) : FileScanner {
    override fun requires(claimedType: ContentType): FileScanner.Requires = FileScanner.Requires.Whole

    companion object {
        init {
            FileScannerSettings.register("clamav") {
                Regex("""clamav://(?<host>[^:/]+):?(?<port>[0-9]+)?/(?<platform>[^/]+)?(\?(?<params>.*))?""").matchEntire(it)
                    ?.let { match ->
                        val host = match.groups.get("host")!!.value
                        val port = match.groups.get("port")?.value?.toInt() ?: 3310
                        val platform = match.groups.get("platform")?.value?.let { Platform.valueOf(it) } ?: Platform.JVM_PLATFORM
                        ClamAvFileScanner { ClamavClient(host, port, platform) }
                    }
                    ?: throw IllegalStateException("Invalid ClamAV. It must follow the pattern: clamav://host[:port]/[UNIX or WINDOWS]")
            }
        }
    }

    override fun scan(claimedType: ContentType, data: InputStream) {
        when(val r = get().scan(data)) {
            ScanResult.OK -> {}
            is ScanResult.VirusFound -> throw BadRequestException("File seems to contain malicious content; ${r.foundViruses.keys.joinToString()}")
        }
    }
}