package com.lightningkite.lightningserver.terraform

//class TerraformDsl(val out: Appendable)
//
////enum class TerraformType {
////    string,
////    boolean
////}
//
//fun TerraformDsl.variableString(name: String, description: String? = null, default: String? = null, sensitive: Boolean = false) {
//    out.appendLine("variable \"$name\" {")
//    description?.let { out.appendLine("  description = \"$it\"") }
//    out.appendLine("  type = string")
//    default?.let { out.appendLine("  default = \"$it\"") }
//    if(sensitive) { out.appendLine("  sensitive = true") }
//    out.appendLine("}")
//}
//
//fun TerraformDsl.output(name: String, value: String) {
//    out.appendLine("output \"$name\" {")
//    out.appendLine("  value = $value")
//    out.appendLine("}")
//}
//
//fun TerraformDsl.resource(name: String, type: String) {
//    out.appendLine("output \"$name\" {")
//    out.appendLine("  value = $value")
//    out.appendLine("}")
//}
//
//fun TerraformDsl.comment(text: String) {
//    out.appendLine("# $text")
//}