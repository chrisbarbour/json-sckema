package sckema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory


private val nodeFactory = JsonNodeFactory.instance
fun JsonSchema.generateWithDefaults() = generateWithDefaults(definitions!!)
private fun JsonSchema.generateWithDefaults(definitions: JsonDefinitions): JsonNode{
    if(`$ref` != null) {
        return (definitions.definitions[`$ref`.substringAfterLast("/")]!! as JsonSchema).generateWithDefaults(definitions)
    }
    else if(allOf != null && allOf.size == 2){
        return allOf[1].generateWithDefaults(definitions)
    }
    val properties = this.properties?.definitions.orEmpty()
    return nodeFactory.objectNode().also { node ->
        properties.forEach { name, definition ->
            if(definition.hasDefaults(definitions, mutableMapOf())){
                when(definition){
                    is JsonSchema -> if(definition.type?.types.orEmpty().firstOrNull() == "object") {
                        node.putPOJO(name, definition.generateWithDefaults(definitions))
                    }
                    else if(definition.type?.types.orEmpty().firstOrNull() == "array") {
                        node.putArray(name).also { array ->
                            definition.items?.schemas.orEmpty().forEach {
                                array.add(it.generateWithDefaults(definitions))
                            }
                        }
                    }
                    else { node.put(name, definition.default.toString()) }
                }
            }
        }
    }
}

private fun JsonOrStringDefinition.hasDefaults(definitions: JsonDefinitions, refInfo: MutableMap<String, Boolean>): Boolean = when(this){
    is JsonSchema -> {
        when(type?.types.orEmpty().firstOrNull()){
            "object" -> this.properties?.definitions.orEmpty().any { it.value.hasDefaults(definitions, refInfo) }
            "array" -> this.items?.schemas.orEmpty().any{ it.hasDefaults(definitions, refInfo) }
            else -> false
        } ||
        if(`$ref` != null){
            if(!refInfo.containsKey(`$ref`)){
                refInfo[`$ref`] = definitions.definitions[`$ref`.substringAfterLast("/")]?.hasDefaults(definitions, refInfo) ?: false
            }
            refInfo[`$ref`]!!
        } else{ false } ||
        allOf?.any { it.hasDefaults(definitions, refInfo) } ?: false ||
        default != null
    }
    else -> false
}