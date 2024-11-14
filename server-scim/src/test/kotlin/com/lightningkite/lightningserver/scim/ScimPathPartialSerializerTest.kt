package com.lightningkite.lightningserver.scim

import com.lightningkite.lightningserver.scim.parse.ScimFilterLexer
import com.lightningkite.lightningserver.scim.parse.ScimFilterParser
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RuleContext
import org.antlr.v4.kotlinruntime.StringCharStream
import org.antlr.v4.kotlinruntime.tree.ParseTree
import org.antlr.v4.kotlinruntime.tree.TerminalNode
import org.junit.Assert.*
import org.junit.Test

class ScimPathPartialSerializerTest {
    init { prepareModelsServerScim() }
    @Test fun simple() {
        assertEquals(
            ScimPath.FieldNullable(
                ScimPath.Field(
                    ScimPath.Base(ScimUser.serializer()),
                    ScimUser_name
                ),
                ScimUserName_formatted
            ),
            ScimPathPartialSerializer(ScimUser.serializer()).fromString("name.formatted")
        )
    }

    @Test fun scimFilterTest() {
        val str = StringCharStream("meta.lastModified gt \"2011-05-13T04:42:34Z\"")
        val lex = ScimFilterLexer(str)
        val stream = CommonTokenStream(lex)
        val parse = ScimFilterParser(stream)
        println(parse.parse().printStructure())
    }
    @Test fun scimFilterTest2() {
        val str = StringCharStream("meta.lastModified gt \"2011-05-13T04:42:34Z\"] more after end")
        val lex = ScimFilterLexer(str)
        val stream = CommonTokenStream(lex)
        val parse = ScimFilterParser(stream)
        println(parse.parse().printStructure())
    }

    private fun ParseTree.printStructure(spaces: Int = 0) {
        print(" ".repeat(spaces))
        (this as? RuleContext)?.let {
            print(it::class.simpleName)
            print(' ')
        } ?: (this as? TerminalNode)?.let {
            print("TERM ${it.symbol.type} ")

        }
        println(this.text)
        for(index in 0..<childCount) {
            getChild(index)?.printStructure(spaces + 1)
        }
    }
}