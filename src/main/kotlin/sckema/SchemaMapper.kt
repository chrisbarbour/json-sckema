package sckema

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
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
        SchemaMapper().map(actualArgs[1], File(it).readText(), yaml = yaml, parent = actualArgs[3]).map { it.writeTo(Paths.get(location)) }
    }
}

data class SchemaMapper(private val typePool: MutableList<TypeSpec> = mutableListOf()){
    private val jackson = jacksonObjectMapper()
    private val yamlJackson = ObjectMapper(YAMLFactory())
    init {
        val module = SimpleModule()
        module.addDeserializer(JsonDefinitions::class.java, DefinitionsDeserializer())
        module.addDeserializer(JsonTypes::class.java, TypesDeserializer())
        module.addDeserializer(JsonItems::class.java, ItemsDeserializer())
        module.addDeserializer(AdditionalProperties::class.java, AdditionalPropertiesDeserializer())
        jackson.registerModule(module)
        yamlJackson.registerModule(module)
    }
    fun map(`package`: String, schemaString: String, yaml: Boolean = true, parent: String? = null): List<FileSpec>{
        val objectMapper = if(yaml) yamlJackson else jackson
        val jsonSchema: JsonSchema = objectMapper.readValue(schemaString)
        return map(`package`, jsonSchema, parent) + ValidationSpec.validationHelpers(`package`)
    }
    
    fun map(`package`: String, schema: JsonSchema, parentName: String? = null): List<FileSpec>{
        val parent = if(schema.properties != null) typeFrom(`package`, parentName!!, schema) else null
        if(schema.definitions != null) definitions(`package`, schema.definitions)
        return (listOfNotNull(parent) + typePool).map { FileSpec.get(`package`,it) }
    }

    private fun nameFrom(name: String) = if(name.startsWith('$')) "`$name`" else name

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
            .addFunctions(funSpecs.filter { it.name != "copy" && it.name != "merge"}.map { if(it.name == "validate") it.open(false) else it })
            .addFunction(
                    FunSpec.builder("copy")
                            .addParameters(
                                    propertySpecs.map {
                                        ParameterSpec.builder(it.name, it.type.asNullable())
                                                .defaultValue("null")
                                                .build()
                                    }
                            )
                            .addCode(
                                    propertySpecs.foldIndexed("return ${name!!}(\n"){
                                        index, code, property -> code + (if(index != 0) ", " else "" ) +"${property.name} = ${property.name} ?: this.${property.name}\n"
                                    } + ")\n"
                            )
                            .returns(ClassName.bestGuess(name!!))
                            .build()
            )
            .addFunction(
                    FunSpec.builder("merge")
                            .addParameter("other",ClassName.bestGuess(name!!))
                            .addCode(
                                    propertySpecs.filter { it.type.nullable }.foldIndexed("return copy(\n"){
                                        index, code, property -> code + (if(index != 0) ", " else "" ) +"${property.name} = ${property.name} ?: other.${property.name}\n"
                                    } + ")\n"
                            )
                            .returns(ClassName.bestGuess(name!!))
                            .build()
            )
            .build()

    private fun additionalPropertyFor(`package`: String, name: String, additionalProperties: AdditionalProperties) =
            if(additionalProperties.include) {
                val type = if(additionalProperties.type != null) typeFrom(`package`, name, additionalProperties.type, true)
                    else Any::class.asTypeName()
                PropertySpec.builder(
                        name = "additionalProperties",
                        type = ParameterizedTypeName.get(java.util.HashMap::class.asClassName(), String::class.asTypeName(), type.asNullable())
                ).addAnnotation(JsonIgnore::class).initializer("HashMap()").build()
            } else null

    private fun additionalPropertyFunctionsFor(`package`: String, name: String, additionalProperties: AdditionalProperties) =
            if(additionalProperties.include){
                val type = (if(additionalProperties.type != null) typeFrom(`package`, name, additionalProperties.type, true)
                else Any::class.asTypeName()).asNullable()
                listOf(
                        FunSpec.builder("set").addAnnotation(JsonAnySetter::class)
                                .addParameter("name", String::class)
                                .addParameter("value", type)
                                .addCode("additionalProperties[name] = value\n")
                                .build(),
                        FunSpec.builder("additionalProperties").addAnnotation(JsonAnyGetter::class)
                                .returns(ParameterizedTypeName.get(java.util.HashMap::class.asClassName(), String::class.asTypeName(), type.asNullable()))
                                .addCode("return additionalProperties\n")
                                .build()
                )
            } else emptyList()

    fun typeFrom(`package`: String, name: String, schema: JsonSchema): TypeSpec?{
        return if(schema.properties != null || schema.additionalProperties.include) {
            val propertyDefinitions = schema.properties?: JsonDefinitions(emptyMap())
            TypeSpec
                    .classBuilder(name).also {
                        if(propertyDefinitions.definitions.isNotEmpty()) it.addModifiers(KModifier.DATA)
                    }
                    .primaryConstructor(constructorFor(`package`, propertyDefinitions, schema.required.orEmpty()))
                    .addProperties(
                            propertyDefinitions.definitions.map {
                                propertyFrom(nameFrom(it.key),`package`, it.value, schema.required.orEmpty().contains(it.key))
                            } + metadataPropertiesFrom(schema) + listOfNotNull(additionalPropertyFor(`package`, name, schema.additionalProperties))
                    )
                    .addFunctions(additionalPropertyFunctionsFor(`package`, name, schema.additionalProperties))
                    .addFunction(ValidationSpec.validationForObject(`package`, schema)!!)
                    .build()
        }
        else if(schema.allOf != null && schema.allOf.first().`$ref` != null){ //special case for extension
            val ref = schema.allOf.first().`$ref`!!.substring("#/definitions/".length)
            val referenced = typePool.find { it.name == ref }
            if(referenced != null){
                typePool.remove(referenced)
                typePool.add(referenced.open())
                typeFromAllOf(`package`, name, schema.allOf, referenced)
            }
            else null
        }
        else null
    }

    private fun metadataPropertiesFrom(schema: JsonSchema): List<PropertySpec>{
        return if(schema.metadata != null){
            schema.metadata.map {
                PropertySpec.builder(it.key, String::class.asTypeName()).addAnnotation(JsonIgnore::class).initializer("%S", it.value).build()
            }
        }
        else emptyList()
    }

    private fun typeFromAllOf(`package`: String, name: String, schemas: List<JsonSchema>, referenced: TypeSpec): TypeSpec? {
        val properties = schemas[1].properties!!
        val required = schemas[1].required
        val additionalProperties = schemas[1].additionalProperties
        return TypeSpec
                .classBuilder(name)
                .addModifiers(KModifier.DATA)
                .primaryConstructor(
                        constructorFor(`package`, properties, required.orEmpty())
                                .toBuilder()
                                .addParameters(referenced.primaryConstructor!!.parameters.map { it.override()  })
                                .build()
                )
                .addProperties(
                        properties.definitions.map {
                            propertyFrom(nameFrom(it.key),`package`, it.value, required.orEmpty().contains(it.key))
                        } + referenced.propertySpecs.map { it.override() } + listOfNotNull(additionalPropertyFor(`package`, name, additionalProperties))

                )
                .addFunctions(additionalPropertyFunctionsFor(`package`, name, additionalProperties))
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

    fun definitions(`package`: String, definitions: JsonDefinitions): List<TypeSpec>{
        return definitions.definitions
                .filter { it.value is JsonSchema }
                .map { it.key to it.value as JsonSchema }
                .let {
                    it.filter { it.second.type?.types?.first() == "object" || it.second.allOf != null }
                            .mapNotNull { typeFrom(`package`, it.first, it.second)?.also { typePool.add(it) } }
                }
    }


    private fun constructorFor(`package`: String, definitions: JsonDefinitions, required: List<String>): FunSpec{
        return definitions.definitions.toList().fold(FunSpec.constructorBuilder()){
            acc, definition ->
            val isRequired = required.contains(definition.first)
            val parameter = ParameterSpec.builder(nameFrom(definition.first),typeFrom(`package`, definition.first, definition.second, isRequired))
            var defaultValue = CodeBlock.of("")
            if(definition.second is JsonSchema){
                val def = definition.second as JsonSchema
                if(def.default != null){
                    val type = def.type?.types?.get(0)
                    when(type){
                        "string" -> defaultValue = CodeBlock.of("%S", def.default)
                        "number" -> defaultValue = CodeBlock.of("%T(${def.default})", BigDecimal::class)
                        "integer" -> defaultValue = CodeBlock.of("${def.default}")
                        "boolean" -> defaultValue = CodeBlock.of("${def.default}")
                    }
                }
            }
            if(!isRequired && defaultValue.isEmpty()) defaultValue = CodeBlock.of("null")
            if(defaultValue.isNotEmpty()) parameter.defaultValue(defaultValue)
            acc.addParameter(parameter.build())
        }.build()
    }

    fun propertyFrom(name: String, `package`: String, definition: JsonOrStringDefinition, required: Boolean): PropertySpec{
        return PropertySpec.builder(name,typeFrom(`package`, name, definition, required))
                .addAnnotations(annotationsFrom(definition))
                .initializer(name).build()
    }

    private fun annotationsFrom(definition: JsonOrStringDefinition) =
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

    private fun simpleTypeNameFor(`package`: String, parentName: String, definition: JsonSchema) = when (definition.type?.types?.first()) { // only handling simple types here
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
            when {
                definition.properties != null -> typeFrom(`package`, parentName.capitalizeFirst(), definition).let { if (!typePool.contains(it)) typePool.add(it!!); ClassName(`package`, parentName.capitalizeFirst()) }
                else -> Any::class.asTypeName()
            }
        }
        "array" -> ParameterizedTypeName.get(List::class.asClassName(), typeFrom(`package`, parentName + "Item", definition.items!!.schemas.firstOrNull()
                ?: JsonSchema(), true))
        else -> Any::class.asTypeName()
    }

    fun typeFrom(`package`: String, parentName: String, definition: JsonOrStringDefinition, required: Boolean): TypeName{
        if(definition is JsonSchema) {
            val typeName = when {
                definition.`$ref` != null -> ClassName(`package`, definition.`$ref`.substring("#/definitions/".length))
                definition.type == null -> Any::class.asTypeName()
                else -> simpleTypeNameFor(`package`, parentName, definition)
            }
            return if(required) typeName
            else typeName.asNullable()
        }
        throw IllegalArgumentException("Definition is not a Schema")
    }
}