
import com.squareup.kotlinpoet.*
import org.junit.Test
import sckema.*
import java.io.File
import java.math.BigDecimal
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.test.expect

class SchemaMapperTest{

//    @Test
//    fun go(){
//        val text = SchemaMapperTest::class.java.classLoader.getResourceAsStream("swagger-test.yml").bufferedReader().readText()
//        SchemaMapper().map("test", text, yaml = true, parent = "Policy").map { it.writeTo(Paths.get("/Users/n0237573/Code/titan/json-sckema/target/generated-sources")) }
//    }

    @Test
    fun `should include additionalProperties of type Map -String Any- by default`(){
        val schema = JsonSchema(type = JsonTypes(listOf("object")),
            properties = JsonDefinitions(definitions = mapOf(
                    "B" to JsonSchema(type = JsonTypes(listOf("string")))
            ) )
        )
        val typeSpec = SchemaMapper().map("abc", schema, "A")[0].members[0] as TypeSpec
        with(typeSpec.propertySpecs){
            expect(2){ count() }
            with(get(0)){ expect("B"){ name } }
            with(get(1)){
                expect("additionalProperties"){ name }
                expect("HashMap()"){ initializer.toString() }
                expect(ParameterizedTypeName.get(HashMap::class.asClassName(), String::class.asTypeName(), Any::class.asTypeName().asNullable())){ type }
            }
        }
        with(typeSpec.primaryConstructor!!){
            expect(1){ parameters.size }
            expect("B") { parameters[0].name }
        }
    }

    @Test
    fun `should create simple type with one field that can be null`(){
        val schema = JsonSchema(definitions = JsonDefinitions(definitions = mapOf(
                "A" to JsonSchema(
                        type = JsonTypes(listOf("object")),
                        additionalProperties = AdditionalProperties(false),
                        properties = JsonDefinitions(definitions = mapOf(
                                "B" to JsonSchema(type = JsonTypes(listOf("string"))),
                                "\$id" to JsonSchema(type = JsonTypes(listOf("string")))
                        ) )
                ))
        ))
        val files = SchemaMapper().map("abc", schema)
        expect(1){ files.count() }
        with(files[0]){
            expect("abc"){ packageName }
            expect("A"){ name }
            expect(1){ members.count() }
            with(members[0]){
                expect(true){ this is TypeSpec }
                with(this as TypeSpec){
                    expect("A"){ name }
                    expect(2){ propertySpecs.count() }
                    with(this.primaryConstructor){
                        with(this!!.parameters[0]){
                            expect("B"){ name }
                            expect(String::class.asTypeName().asNullable()){ type }
                            expect("null"){ defaultValue.toString() }
                        }
                        with(this.parameters[1]){
                            expect("`\$id`"){ name }
                            expect(String::class.asTypeName().asNullable()){ type }
                            expect("null"){ defaultValue.toString() }
                        }
                    }
                    with(propertySpecs[0]){
                        expect("B"){ name }
                        expect(String::class.asTypeName().asNullable()){ type }
                    }
                    with(propertySpecs[1]){
                        expect("`\$id`"){ name }
                        expect(String::class.asTypeName().asNullable()){ type }
                    }
                }
            }
        }
    }

    private fun simpleTypeTestFor(typeName: String, type: KClass<*>, format: String? = null){
        val typePool = mutableListOf<TypeSpec>()
        val schema = JsonSchema(type = JsonTypes(listOf(typeName)), format = format)
        expect(type.asTypeName()){ SchemaMapper().typeFrom("abc", "parent", schema, true) }
        expect(type.asTypeName().asNullable()){ SchemaMapper().typeFrom("abc", "parent", schema, false) }
        expect(true){ typePool.isEmpty() }
    }

    @Test
    fun `should be String when string schema type`() = simpleTypeTestFor("string", String::class)
    @Test
    fun `should be BigDecimal when number schema type`() = simpleTypeTestFor("number", BigDecimal::class)
    @Test
    fun `should be Integer when integer schema type`() = simpleTypeTestFor("integer", Int::class)
    @Test
    fun `should be Boolean when boolean schema type`() = simpleTypeTestFor("boolean", Boolean::class)

    @Test
    fun `should be LocalDate when string schema type and date format`() = simpleTypeTestFor("string", LocalDate::class, "date")

    @Test
    fun `should be LocalDateTime when string schema type and date-time format`() = simpleTypeTestFor("string", LocalDateTime::class, "date-time")

    @Test
    fun `should be Item when ref schema`(){
        val typePool = mutableListOf<TypeSpec>()
        val schema = JsonSchema(`$ref` = "#/definitions/Item")
        expect(ClassName("abc", "Item")){ SchemaMapper().typeFrom("abc", "parent", schema, true) }
        expect(ClassName("abc", "Item").asNullable()){ SchemaMapper().typeFrom("abc", "parent", schema, false) }
        expect(true){ typePool.isEmpty() }
    }

    @Test
    fun `should be List-String- when array with simple type string under items`(){
        val typePool = mutableListOf<TypeSpec>()
        val schema = JsonSchema(type = JsonTypes(listOf("array")), items = JsonItems(listOf(JsonSchema(type = JsonTypes(listOf("string"))))))
        expect(ParameterizedTypeName.get(List::class, String::class)){ SchemaMapper().typeFrom("abc", "parent", schema, true) }
        expect(true){ typePool.isEmpty() }
    }

    @Test
    fun `should be annotated with serializer & deserializer for date formatted string`(){
        val schema = JsonSchema(type = JsonTypes(listOf("string")), format = "date")

        val properties: PropertySpec = SchemaMapper().propertyFrom("abc", "parent", schema, true)

        expect(2) {properties.annotations.size }
        expect(true) {properties.annotations.map { a -> a.toString() }.contains("@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer::class)") }
        expect(true) {properties.annotations.map { a -> a.toString() }.contains("@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer::class)") }
    }

    @Test
    fun `should be annotated with serializer & deserializer for date-time formatted string`(){
        val schema = JsonSchema(type = JsonTypes(listOf("string")), format = "date-time")

        val properties: PropertySpec = SchemaMapper().propertyFrom("abc", "parent", schema, true)

        expect(2) {properties.annotations.size }
        expect(true) {properties.annotations.map { a -> a.toString() }.contains("@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer::class)") }
        expect(true) {properties.annotations.map { a -> a.toString() }.contains("@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer::class)") }
    }

    @Test
    fun `should not be annotated for non-date string`(){
        val schema = JsonSchema(type = JsonTypes(listOf("string")))

        val properties: PropertySpec = SchemaMapper().propertyFrom("abc", "parent", schema, true)

        expect(0) {properties.annotations.size }
    }

    @Test
    fun `should be List-OtherItem- when array with object type and OtherItem should be generated`(){
        val typePool = mutableListOf<TypeSpec>()
        val schema = JsonSchema(type = JsonTypes(listOf("array")), items = JsonItems(listOf(JsonSchema(type = JsonTypes(listOf("object")), additionalProperties = AdditionalProperties(false), properties = JsonDefinitions(mapOf("a" to JsonSchema(type = JsonTypes(listOf("string")))))))))
        expect(ParameterizedTypeName.get(List::class.asClassName(), ClassName.bestGuess("abc.OtherItem"))){ SchemaMapper(typePool).typeFrom("abc", "Other", schema, true) }
        expect(1){ typePool.count() }
        with(typePool[0]){
            expect("OtherItem"){ name }
            expect(1){ propertySpecs.count() }
            with(this.primaryConstructor){
                with(this!!.parameters[0]){
                    expect("a"){ name }
                    expect(String::class.asTypeName().asNullable()){ type }
                    expect("null"){ defaultValue.toString() }
                }
            }
            with(propertySpecs[0]){
                expect("a"){ name }
                expect(String::class.asTypeName().asNullable()){ type }
            }
        }
    }

    @Test
    fun `should generate two objects when one has an object property`(){
        val typePool = mutableListOf<TypeSpec>()
        val schema = JsonSchema(
                type = JsonTypes(listOf("object")),
                additionalProperties = AdditionalProperties(false),
                properties = JsonDefinitions(mapOf(
                        "a" to JsonSchema(
                                type = JsonTypes(listOf("object")),
                                additionalProperties = AdditionalProperties(false),
                                properties = JsonDefinitions(mapOf(
                                        "b" to JsonSchema(
                                                type = JsonTypes(listOf("string"))
                                        )))
                        )))
        )
        expect(ClassName("abc","T")){ SchemaMapper(typePool).typeFrom("abc", "T", schema, true) }
        expect(2){ typePool.count() }
        with(typePool[0]){
            expect("A"){ name }
            expect(1){ propertySpecs.count() }
            with(this.primaryConstructor){
                with(this!!.parameters[0]){
                    expect("b"){ name }
                    expect(String::class.asTypeName().asNullable()){ type }
                    expect("null"){ defaultValue.toString() }
                }
            }
            with(propertySpecs[0]){
                expect("b"){ name }
                expect(String::class.asTypeName().asNullable()){ type }
            }
        }
        with(typePool[1]){
            expect("T"){ name }
            expect(1){ propertySpecs.count() }
            with(this.primaryConstructor){
                with(this!!.parameters[0]){
                    expect("a"){ name }
                    expect(ClassName("abc","A").asNullable()){ type }
                    expect("null"){ defaultValue.toString() }
                }
            }
            with(propertySpecs[0]){
                expect("a"){ name }
                expect(ClassName("abc","A").asNullable()){ type }
            }
        }
    }

    @Test
    fun `should extend when using allOf`(){
        val nameSchema = JsonSchema(
                type = JsonTypes(listOf("object")),
                properties = JsonDefinitions(mapOf(
                        "name" to JsonSchema(
                                type = JsonTypes(listOf("string"))
                        )))
        )

        val objectSchema = JsonSchema(
                allOf = listOf(
                        JsonSchema(`$ref` = "#/definitions/NameInfo"),
                        JsonSchema(
                                type = JsonTypes(listOf("object")),
                                properties = JsonDefinitions(mapOf(
                                        "other" to JsonSchema(
                                                type = JsonTypes(listOf("string"))
                                        )))
                        )
                )
        )

        val nameInfoType = SchemaMapper().typeFrom("","NameInfo", nameSchema)!!
        val objectType = SchemaMapper(mutableListOf(nameInfoType)).typeFrom("", "ObjectInfo", objectSchema)
        println(objectType)
    }
}
