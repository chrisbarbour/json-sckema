package sckema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.squareup.kotlinpoet.*
import java.io.File
import java.math.BigDecimal
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.KClass

// 0 - Location
// 1 - Package
// 2 - Format
// 3... - Schema Resources
fun main(args: Array<String>){
    val actualArgs = if(args.isEmpty()) arrayOf("${System.getProperty("user.dir")}/target/generated-sources", "sckema","yaml", "${System.getProperty("user.dir")}/src/main/resources/swagger-test.yml") else args
    actualArgs.map { println(it) }
    val location = actualArgs[0]
    File(location).mkdir()
    val yaml = with(actualArgs[2]){ this == "yaml" || this == "yml"}

    actualArgs.asList().subList(3, actualArgs.size).forEach {
        SchemaMapper.map(actualArgs[1], File(it).readText(), yaml = yaml).map { it.writeTo(Paths.get(location)) }
    }
}

fun <T: Any> KClass<T>.resource(resource: String) = String(this.java.classLoader.getResourceAsStream(resource).readBytes())

object SchemaMapper{

    fun map(`package`: String, schemaString: String, yaml: Boolean = true): List<FileSpec>{
        val objectMapper = if(yaml) yamlJackson else jackson
        val jsonSchema: JsonSchema = objectMapper.readValue(schemaString)
        return map(`package`, jsonSchema, mutableListOf()) + ValidationSpec.validationHelpers(`package`)
    }

    fun map(`package`: String, schema: JsonSchema, typePool: MutableList<TypeSpec>): List<FileSpec>{
        val type = schema.type ?: "object"
        val definitions = if(schema.definitions != null && type == "object"){
            definitions(`package`, schema.definitions, typePool)
        }
        else emptyList()
        val name = schema.`$id`?.substringAfterLast("/")?.substringBeforeLast(".json") ?: "Unknown"
        val types = definitions + listOfNotNull(typeFrom(`package`, name, schema, typePool))
        val totalTypes = types + typePool.filter { types.find { type -> type.name === it.name } == null }

        return totalTypes.map { FileSpec.get(`package`,it) }
    }

    fun typeFrom(`package`: String, name: String, schema: JsonSchema, typePool: MutableList<TypeSpec>): TypeSpec?{
        if(schema.properties != null) {
            return TypeSpec
                    .classBuilder(name)
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(constructorFor(`package`, schema.properties, schema.required.orEmpty(), typePool))
                    .addProperties(schema.properties.definitions.map { propertyFrom(it.key,`package`, it.value, schema.required.orEmpty().contains(it.key), typePool) })
                    .addFunction(ValidationSpec.validationForObject(`package`, schema))
                    .build()
        }
        return null
    }

    fun definitions(`package`: String, definitions: JsonDefinitions, typePool: MutableList<TypeSpec>): List<TypeSpec>{
        return definitions.definitions
                .filter { it.value is JsonSchema }
                .map { it.key to it.value as JsonSchema }
                .let {
                    it.filter { it.second.type?.types?.first() == "object" }
                    .mapNotNull { typeFrom(`package`, it.first, it.second, typePool)?.also { typePool.add(it) } }
                }
    }

    fun constructorFor(`package`: String, definitions: JsonDefinitions, required: List<String>, typePool: MutableList<TypeSpec>): FunSpec{
        return definitions.definitions.toList().fold(FunSpec.constructorBuilder()){
            acc, definition ->
            val isRequired = required.contains(definition.first)
            val parameter = ParameterSpec.builder(definition.first,typeFrom(`package`, definition.first, definition.second, isRequired, typePool))
            if(!isRequired) parameter.defaultValue("null")
            acc.addParameter(parameter.build())
        }.build()
    }

    fun propertyFrom(name: String, `package`: String, definition: JsonDefinition, required: Boolean, typePool: MutableList<TypeSpec>): PropertySpec{
        return PropertySpec.builder(name,typeFrom(`package`, name, definition, required, typePool))
                .addAnnotations(annotationsFrom(definition))
                .initializer(name).build()
    }

    fun annotationsFrom(definition: JsonDefinition) =
            if(definition is JsonSchema) {
            when (definition.format) {
                "date" -> listOf(
                        AnnotationSpec.builder(JsonDeserialize::class.java).addMember("using = %T::class", LocalDateDeserializer::class.java).build(),
                        AnnotationSpec.builder(JsonSerialize::class.java).addMember("using = %T::class", LocalDateSerializer::class.java).build()
                )
                "date-time" -> listOf(
                        AnnotationSpec.builder(JsonDeserialize::class.java).addMember("using = %T::class", LocalDateTimeDeserializer::class.java).build(),
                        AnnotationSpec.builder(JsonSerialize::class.java).addMember("using = %T::class", LocalDateTimeSerializer::class.java).build()
                )
                else -> emptyList()
            }
        }
        else throw IllegalArgumentException()

    private fun String.capitalizeFirst() = this[0].toUpperCase() + this.substring(1)

    fun typeFrom(`package`: String, parentName: String, definition: JsonDefinition, required: Boolean, typePool: MutableList<TypeSpec>): TypeName{
        if(definition is JsonSchema) {
            val typeName = if(definition.`$ref` != null){
                ClassName(`package`, definition.`$ref`.substring("#/definitions/".length))
            }
            else {
                when (definition.type!!.types.first()) { // only handling simple types here
                    "string" -> {
                        when (definition.format) {
                            "date" -> LocalDate::class.asTypeName()
                            "date-time" -> LocalDateTime::class.asTypeName()
                            else -> String::class.asTypeName()
                        }
                    }
                    "number" -> BigDecimal::class.asTypeName()
                    "integer" -> Int::class.asTypeName()
                    "boolean" -> Boolean::class.asTypeName()
                    "object" -> {
                        when{
                            definition.properties != null -> typeFrom(`package`, parentName.capitalizeFirst(), definition, typePool).let { if(!typePool.contains(it))typePool.add(it!!); ClassName(`package`, parentName.capitalizeFirst()) }
                            else -> Any::class.asTypeName()
                        }
                    }
                    "array" -> ParameterizedTypeName.get(List::class.asClassName(), typeFrom(`package`,parentName+"Item",definition.items!!.schemas.first(), true, typePool))
                    else -> throw IllegalArgumentException()
                }
            }
            return if(required) typeName
            else typeName.asNullable()
        }
        throw IllegalArgumentException()
    }

    private val jackson = jacksonObjectMapper()
    private val yamlJackson = ObjectMapper(YAMLFactory())
    init {
        val module = SimpleModule()
        module.addDeserializer(JsonDefinitions::class.java, DefinitionsDeserializer())
        module.addDeserializer(JsonTypes::class.java, TypesDeserializer())
        module.addDeserializer(JsonItems::class.java, ItemsDeserializer())
        jackson.registerModule(module)
        yamlJackson.registerModule(module)
    }
}
