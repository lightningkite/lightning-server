package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.encryption.SecretBasis

public val secretBasis: ServerSetting.Direct<SecretBasis> =
    ServerSetting("secretBasis", SecretBasis(), SecretBasis.serializer())

public val generalSettings: ServerSetting.Direct<GeneralServerSettings> =
    ServerSetting("general", GeneralServerSettings(), GeneralServerSettings.serializer())
