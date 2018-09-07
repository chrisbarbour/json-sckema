package sckema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory

private val nodeFactory = JsonNodeFactory.instance
fun JsonNode.merge(jsonNode: JsonNode): JsonNode = if(this.isArray){
    nodeFactory.arrayNode().also {
        this.forEachIndexed { index, leftNode ->
            if(jsonNode.count() > index) it.add(leftNode.merge(jsonNode[index]))
            else it.add(leftNode)
        }
    }
} else nodeFactory.objectNode().also {
    val leftFields = this.fields().asSequence().toList().map { it.key to it.value }.toMap()
    val rightFields = jsonNode.fields().asSequence().toList().map { it.key to it.value }.toMap()
    val rightSoloFields = rightFields.filter { !leftFields.containsKey(it.key) }
    val mergableFields = rightFields.filter { leftFields.containsKey(it.key) }
    val leftSoloFields = leftFields.filter { !rightFields.containsKey(it.key) }
    (leftSoloFields + rightSoloFields).forEach { name, value ->  it.put(name,value) }
    mergableFields.forEach { name, value -> it.put(name, leftFields[name]!!.merge(value)) }
}