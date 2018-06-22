package sckema

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.math.BigDecimal

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonSchema(
        val `$id`: String? = null,
        val title: String? = null,
        val description: String? = null,
        val default: Any? = null,
        val `$ref`: String? = null,
        @JsonDeserialize(using = TypesDeserializer::class) val type: JsonTypes? = null,
        @JsonDeserialize(using = ItemsDeserializer::class) val items: JsonItems? = null,
        val format: String? = null,
        val enum: List<String>? = null,
        val maxLength: Int? = null,
        val minLength: Int? = null,
        val pattern: String? = null,
        val required: List<String>? = null,
        val additionalItems: Boolean = true,
        val uniqueItems: Boolean? = null,
        val multipleOf: BigDecimal? = null,
        val maximum: BigDecimal? = null,
        val exclusiveMaximum: BigDecimal? = null,
        val minimum: BigDecimal? = null,
        val exclusiveMinimum: BigDecimal? = null,
        private val `$comment`: String? = null,
        @JsonDeserialize(using = DefinitionsDeserializer::class) val definitions: JsonDefinitions? = null,
        @JsonDeserialize(using = DefinitionsDeserializer::class) val properties: JsonDefinitions? = null
): JsonDefinition


interface JsonDefinition
data class JsonStringDefinition(val value: String = ""): JsonDefinition
data class JsonDefinitions(val definitions: Map<String, JsonDefinition>)
data class JsonTypes(val types: List<String>)
data class JsonItems(val schemas: List<JsonSchema>)

class DefinitionsDeserializer: JsonDeserializer<JsonDefinitions>(){
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonDefinitions {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        val map = mutableMapOf<String, JsonDefinition>()
        node.fieldNames().forEach {
            map[it] = when{
                node[it].isTextual -> JsonStringDefinition(node[it].asText())
                else -> codec.treeToValue(node[it], JsonSchema::class.java)
            }
        }
        return JsonDefinitions(map)
    }
}

class TypesDeserializer: JsonDeserializer<JsonTypes>(){
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonTypes {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        return when{
            node.isTextual -> JsonTypes(listOf(node.textValue()))
            else -> JsonTypes(node.fields().asSequence().map { it.value.textValue() }.toList())
        }
    }
}

class ItemsDeserializer: JsonDeserializer<JsonItems>(){
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonItems {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        val schemas = if(node.isArray) node.fields().asSequence().map { it.value }.toList() else listOf(node)
        return JsonItems( schemas.map { codec.treeToValue(it, JsonSchema::class.java) } )
    }
}

