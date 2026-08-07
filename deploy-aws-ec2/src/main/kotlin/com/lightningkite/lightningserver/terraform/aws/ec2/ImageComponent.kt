package com.lightningkite.lightningserver.terraform.aws.ec2

/**
 * A component to include in an EC2 Image Builder recipe. [arnOrName] is either an AWS-managed component's
 * short name (expanded to the latest version) or a full component ARN; [parameters] are the component's
 * input parameters, if any.
 */
public data class ImageComponent(
    public val arnOrName: String,
    public val parameters: Map<String, String> = emptyMap(),
)

/** DISA STIG hardening severity for the Amazon-managed `stig-build-linux` component. */
public enum class StigLevel(public val parameterValue: String) {
    Low("Low"),
    Medium("Medium"),
    High("High"),
}

/**
 * Shortcut for the Amazon-managed parameterized Linux STIG hardening component (`stig-build-linux`).
 * Replaces the retired `stig-build-linux-low`/`-medium`/`-high` components, which AWS consolidated into one
 * component with a [Level][StigLevel] parameter. Add it to `hardeningComponents` so it runs last, after the
 * image is fully built — e.g. `override val hardeningComponents = listOf(stigBuildLinux(StigLevel.Low))`.
 *
 * `-medium`/`-high` add aggressive controls (e.g. `noexec` /tmp, auditd-halt) that can break a JVM service;
 * test deliberately before using them.
 *
 * @param installPackages install extra packages required for maximum compliance (component default: off).
 * @param setDoDConsentBanner show the DoD consent banner on login (component default: off).
 */
public fun stigBuildLinux(
    level: StigLevel,
    installPackages: Boolean = false,
    setDoDConsentBanner: Boolean = false,
): ImageComponent = ImageComponent(
    arnOrName = "stig-build-linux",
    parameters = buildMap {
        put("Level", level.parameterValue)
        if (installPackages) put("InstallPackages", "Yes")
        if (setDoDConsentBanner) put("SetDoDConsentBanner", "Yes")
    },
)
