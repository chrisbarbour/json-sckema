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

    actualArgs.asList().subList(4, actualArgs.size).forEach {
        SchemaMapper.map(actualArgs[1], File(it).readText(), yaml = yaml, parent = actualArgs[3]).map { it.writeTo(Paths.get(location)) }
    }
}

fun <T: Any> KClass<T>.resource(resource: String) = String(this.java.classLoader.getResourceAsStream(resource).readBytes())

object SchemaMapper{

    fun map(`package`: String, schemaString: String, yaml: Boolean = true, parent: String? = null): List<FileSpec>{
        val objectMapper = if(yaml) yamlJackson else jackson
        val jsonSchema: JsonSchema = objectMapper.readValue(schemaString)
        return map(`package`, jsonSchema, mutableListOf(), parent) + ValidationSpec.validationHelpers(`package`)
    }

    fun map(`package`: String, schema: JsonSchema, typePool: MutableList<TypeSpec>, parentName: String? = null): List<FileSpec>{
        val parent = if(schema.properties != null) typeFrom(`package`, parentName!!, schema, typePool) else null
        definitions(`package`, schema.definitions!!, typePool)
        return (listOfNotNull(parent) + typePool).map { FileSpec.get(`package`,it) }
    }

    fun nameFrom(name: String) = if(name.startsWith('$')) "`$name`" else name

    private fun FunSpec.open(all: Boolean = false) = FunSpec.builder(name)
            .addParameters(parameters.map { if(all) it.open() else it })
            .addCode(this.body)
            .also {
                if(this.returnType != null) it.returns(this.returnType!!)
                if(!all) it.addModifiers(*(modifiers + KModifier.OPEN).toTypedArray())
            }
            .build()

    private fun PropertySpec.open() =
            if(!modifiers.contains(KModifier.OPEN)) this.toBuilder()
                    .addModifiers(KModifier.OPEN).build() else this
    private fun ParameterSpec.open() =
            if(!modifiers.contains(KModifier.OPEN)) this.toBuilder()
                    .also { if(this.defaultValue != null)it.defaultValue(this.defaultValue!!) }
                    .addModifiers(KModifier.OPEN).build() else this

    private fun ParameterSpec.override() = ParameterSpec.builder(name,type,KModifier.OVERRIDE)
            .also { if(defaultValue != null) it.defaultValue(defaultValue!!) }
            .build()

    private fun PropertySpec.override() = PropertySpec.builder(name,type,KModifier.OVERRIDE)
            .also { if(initializer != null) it.initializer(initializer!!) }
            .build()

    private fun TypeSpec.open() = TypeSpec
            .classBuilder(name!!)
            .addModifiers(*(this.modifiers + KModifier.OPEN - KModifier.DATA).toTypedArray())
            .primaryConstructor(primaryConstructor!!.open(true))
            .superclass(superclass)
            .also { type ->
                superclassConstructorParameters.forEach { type.addSuperclassConstructorParameter(it) }
            }
            .addProperties(propertySpecs.map { it.open() })
            .addFunctions(funSpecs.map { if(it.name == "validate") it.open(false) else it })
            .build()

    fun typeFrom(`package`: String, name: String, schema: JsonSchema, typePool: MutableList<TypeSpec>): TypeSpec?{
        return if(schema.properties != null) {
            TypeSpec
                .classBuilder(name).also {
                    if(schema.properties.definitions.isNotEmpty()) it.addModifiers(KModifier.DATA)
                }
                .primaryConstructor(constructorFor(`package`, schema.properties, schema.required.orEmpty(), typePool))
                .addProperties(schema.properties.definitions.map { propertyFrom(nameFrom(it.key),`package`, it.value, schema.required.orEmpty().contains(it.key), typePool) })
                .addFunction(ValidationSpec.validationForObject(`package`, schema)!!)
                .build()
        }
        else if(schema.allOf != null && schema.allOf.first().`$ref` != null){ //special case for extension
            val ref = schema.allOf.first().`$ref`!!.substring("#/definitions/".length)
            val referenced = typePool.find { it.name == ref }
            if(referenced != null){
                typePool.remove(referenced)
                typePool.add(referenced.open())
                typeFromAllOf(`package`, name, schema.allOf, referenced, typePool)
            }
            else null
        }
        else null
    }

    fun typeFromAllOf(`package`: String, name: String, schemas: List<JsonSchema>, referenced: TypeSpec, typePool: MutableList<TypeSpec>): TypeSpec? {
        val properties = schemas[1].properties!!
        val required = schemas[1].required
        return TypeSpec
                .classBuilder(name)
                .addModifiers(KModifier.DATA)
                .primaryConstructor(
                        constructorFor(`package`, properties, required.orEmpty(), typePool)
                            .toBuilder()
                            .addParameters(referenced.primaryConstructor!!.parameters.map { it.override()  })
                            .build()
                )
                .addProperties(
                        properties.definitions.map {
                            propertyFrom(nameFrom(it.key),`package`, it.value, required.orEmpty().contains(it.key), typePool)
                        } + referenced.propertySpecs.map { it.override() }

                )
                .also {
                    val validate = ValidationSpec.validationForObject(`package`, schemas[1], true)
                    if(validate != null) it.addFunction(validate)
                }
                .superclass(ClassName(`package`,referenced.name!!))
                .also { type ->
                    referenced.primaryConstructor!!.parameters.forEach {
                        type.addSuperclassConstructorParameter(it.name)
                    }
                }
                .build()
    }

    fun definitions(`package`: String, definitions: JsonDefinitions, typePool: MutableList<TypeSpec>): List<TypeSpec>{
        return definitions.definitions
                .filter { it.value is JsonSchema }
                .map { it.key to it.value as JsonSchema }
                .let {
                    it.filter { it.second.type?.types?.first() == "object" || it.second.allOf != null }
                    .mapNotNull { typeFrom(`package`, it.first, it.second, typePool)?.also { typePool.add(it) } }
                }
    }


    fun constructorFor(`package`: String, definitions: JsonDefinitions, required: List<String>, typePool: MutableList<TypeSpec>): FunSpec{
        return definitions.definitions.toList().fold(FunSpec.constructorBuilder()){
            acc, definition ->
            val isRequired = required.contains(definition.first)
            val parameter = ParameterSpec.builder(nameFrom(definition.first),typeFrom(`package`, definition.first, definition.second, isRequired, typePool))
            var defaultValue = ""
            if(definition.second is JsonSchema){
                val def = definition.second as JsonSchema
                if(def.default != null){
                    val type = def.type?.types?.get(0)
                    when(type){
                        "string" -> defaultValue = "\"${def.default}\""
                    }
                }
            }
            if(!isRequired && defaultValue.isEmpty()) defaultValue = "null"
            if(defaultValue.isNotEmpty()) parameter.defaultValue(defaultValue)
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
            else if(definition.type == null) Any::class.asTypeName()
            else {
                when (definition.type.types.first()) { // only handling simple types here
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
                    "array" -> ParameterizedTypeName.get(List::class.asClassName(), typeFrom(`package`,parentName+"Item",definition.items!!.schemas.firstOrNull() ?: JsonSchema(), true, typePool))
                    else -> Any::class.asTypeName()
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
