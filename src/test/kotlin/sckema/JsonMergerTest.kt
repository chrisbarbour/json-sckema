package sckema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Test
import kotlin.math.exp
import kotlin.test.expect

class JsonMergerTest{

    private val mapper = jacksonObjectMapper()
    private operator fun String.unaryMinus() = mapper.readValue<JsonNode>(this)
    //private operator fun JsonNode.unaryMinus() = mapper.readValue<JsonNode>(this)
    @Test
    fun `should merge to empty object from two empty objects`(){
        expect(-"{}"){ (-"{}").merge(-"{}") }
    }

    @Test
    fun `should carry left field when not in right`(){
        expect(-"{\"a\":\"abc\"}"){ (-"{\"a\":\"abc\"}").merge(-"{}") }
    }

    @Test
    fun `should carry right field when not in left`(){
        expect(-"{\"b\":\"abc\"}"){ (-"{}").merge(-"{\"b\":\"abc\"}") }
    }

    @Test
    fun `should carry both fields`(){
        expect(-"{\"a\":\"abc\", \"b\":\"abc\"}"){ (-"{\"a\":\"abc\"}").merge(-"{\"b\":\"abc\"}") }
    }

    @Test
    fun `should carry left array`(){
        expect(-"{\"a\":[\"1\", \"2\"]}"){ (-"{\"a\":[\"1\", \"2\"]}").merge(-"{}") }
    }

    @Test
    fun `should merge object field`(){
        expect(-"{\"a\":{\"b\":\"abc\", \"c\":\"def\"}}"){ (-"{\"a\":{\"b\":\"abc\"}}").merge(-"{\"a\":{\"c\":\"def\"}}") }
    }

    @Test
    fun `should merge array items`(){
        expect(-"{\"a\":[{\"b\":\"abc\"}, {\"c\":\"def\", \"d\":\"ghi\"}]}"){ (-"{\"a\":[{\"b\":\"abc\"}, {\"c\":\"def\"}]}").merge(-"{\"a\":[{}, {\"d\":\"ghi\"}]}") }
    }
}