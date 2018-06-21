package sckema

import com.squareup.kotlinpoet.*
import java.math.BigDecimal


object ValidationSpec{

    fun enumFrom(name: String, entries: List<String>) =
            TypeSpec.enumBuilder(name).also { enum ->
                entries.forEach { enum.addEnumConstant(it) }
            }

    fun validationForObject(`package`: String, schema: JsonSchema): FunSpec{
        val requiredList = schema.required.orEmpty()
        val validations = (schema.properties as JsonDefinitions).definitions
                .map { it.key to it.value as JsonSchema }
                .flatMap {
                    val required = requiredList.contains(it.first)
                    when(it.second.type?.types?.first()){
                        "string" -> stringValidation(it.first, required, it.second)
                        "number" -> numberValidation(it.first, required, it.second)
                        "integer" -> numberValidation(it.first, required, it.second)
                        else ->
                            if(it.second.`$ref` != null)
                                listOf(CodeBlock.of("   ${it.first}${if(required) "" else "?"}.let{ it.validate(\"${it.first}\").asChildrenOf(name) }"))
                            else emptyList()
                    }
                }
        val functionBuilder = FunSpec.builder("validate")
                .addParameter("name", String::class)
                .returns(ClassName(`package`, "Validation"))
        if(validations.isEmpty()){
            functionBuilder.addCode("return Valid()\n")
        }
        else {
            functionBuilder.addCode("val validations = listOfNotNull(\n")
            validations.forEachIndexed { index, codeBlock ->
                functionBuilder.addCode(codeBlock).addCode(if (index < validations.size - 1) ",\n" else "\n")
            }
            functionBuilder.addCode(").flatten()\nreturn if(validations.isEmpty()) Valid() else Invalid(name = name, validationErrors = validations)")
        }
        return functionBuilder.build()
    }

    private fun stringValidation(name: String, required: Boolean, schema: JsonSchema): List<CodeBlock>{
        return listOfNotNull(
                if(schema.maxLength != null) CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( it.length > ${schema.maxLength} ) listOf(ValidationError(\"$name\", ValidationReason.STRING_LENGTH, ValidationError.messageForStringMax(${schema.maxLength}))) else null }") else null,
                if(schema.minLength != null) CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( it.length < ${schema.minLength} ) listOf(ValidationError(\"$name\", ValidationReason.STRING_LENGTH, ValidationError.messageForStringMin(${schema.minLength}))) else null }") else null,
                if(schema.pattern != null) CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( !it.matches( %T(\"${schema.pattern}\") ) ) listOf(ValidationError(\"$name\", ValidationReason.STRING_PATTERN, ValidationError.messageForStringPattern(\"${schema.pattern}\"))) else null }", Regex::class) else null
        )
    }

    private fun numberValidation(name: String, required: Boolean, schema: JsonSchema): List<CodeBlock>{
        return listOfNotNull(
                if(schema.minimum != null)CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( it <= ${schema.minimum} ) listOf(ValidationError(\"$name\", ValidationReason.NUMBER_LIMIT, ValidationError.messageForNumberMin(${schema.minimum}))) else null }") else null,
                if(schema.exclusiveMinimum != null)CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( it < ${schema.exclusiveMinimum} ) listOf(ValidationError(\"$name\", ValidationReason.NUMBER_LIMIT, ValidationError.messageForNumberExclusiveMin(${schema.exclusiveMinimum}))) else null }") else null,
                if(schema.maximum != null)CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( it >= ${schema.maximum} ) listOf(ValidationError(\"$name\", ValidationReason.NUMBER_LIMIT, ValidationError.messageForNumberMax(${schema.maximum}))) else null }") else null,
                if(schema.exclusiveMaximum != null)CodeBlock.of("   $name${if(required) "" else "?"}.let{ if( it < ${schema.exclusiveMaximum} ) listOf(ValidationError(\"$name\", ValidationReason.NUMBER_LIMIT, ValidationError.messageForNumberExclusiveMax(${schema.exclusiveMaximum}))) else null }") else null
        )
    }

    fun validationHelpers(`package`: String) = FileSpec.builder(`package`, "Validation")
            .addType(TypeSpec.interfaceBuilder("Validation").build())
            .addType(TypeSpec.classBuilder("Valid").addSuperinterface(ClassName(`package`,"Validation")).build())
            .addType(TypeSpec.enumBuilder("ValidationReason")
                    .addEnumConstant("STRING_LENGTH")
                    .addEnumConstant("STRING_PATTERN")
                    .addEnumConstant("NUMBER_LIMIT")
                    .build())
            .addType(TypeSpec.classBuilder("ValidationError").addModifiers(KModifier.DATA)
                    .addProperty(PropertySpec.builder("property", String::class.asTypeName()).initializer(CodeBlock.of("property")).build())
                    .addProperty(PropertySpec.builder("reason", ClassName(`package`, "ValidationReason")).initializer(CodeBlock.of("reason")).build())
                    .addProperty(PropertySpec.builder("message", String::class.asTypeName()).initializer(CodeBlock.of("message")).build())
                    .primaryConstructor(FunSpec
                            .constructorBuilder()
                            .addParameter(ParameterSpec.builder("property", String::class.asTypeName()).build())
                            .addParameter(ParameterSpec.builder("reason", ClassName(`package`, "ValidationReason")).build())
                            .addParameter(ParameterSpec.builder("message", String::class.asTypeName()).build())
                            .build())
                    .companionObject(
                            TypeSpec.companionObjectBuilder()
                                    .addFunction(FunSpec.builder("messageForStringMax").addParameter("max", Int::class).returns(String::class).addCode("return \"Character length must be less than or equal to \$max\"\n").build())
                                    .addFunction(FunSpec.builder("messageForStringMin").addParameter("min", Int::class).returns(String::class).addCode("return \"Character length must be greater than or equal to \$min\"\n").build())
                                    .addFunction(FunSpec.builder("messageForStringPattern").addParameter("pattern", String::class).returns(String::class).addCode("return \"String must match pattern: \$pattern\"\n").build())
                                    .addFunction(FunSpec.builder("messageForNumberMax").addParameter("max", BigDecimal::class).returns(String::class).addCode("return \"Number must be less than or equal to \$max\"\n").build())
                                    .addFunction(FunSpec.builder("messageForNumberMin").addParameter("min", BigDecimal::class).returns(String::class).addCode("return \"Number must be greater than or equal to \$min\"\n").build())
                                    .addFunction(FunSpec.builder("messageForNumberExclusiveMax").addParameter("max", BigDecimal::class).returns(String::class).addCode("return \"Number must be less than \$max\"\n").build())
                                    .addFunction(FunSpec.builder("messageForNumberExclusiveMin").addParameter("min", BigDecimal::class).returns(String::class).addCode("return \"Number must be greater than \$min\"\n").build())
                                    .build()
                    )
                    .build()
            )
            .addType(TypeSpec.classBuilder("Invalid").addModifiers(KModifier.DATA).addSuperinterface(ClassName(`package`,"Validation"))
                    .addProperty(PropertySpec.builder("name", String::class).initializer("name").build())
                    .addProperty(PropertySpec.builder("validationErrors", ParameterizedTypeName.get(List::class.asClassName(), ClassName(`package`,"ValidationError"))).initializer(CodeBlock.of("validationErrors")).build())
                    .primaryConstructor(FunSpec
                            .constructorBuilder()
                            .addParameter("name", String::class)
                            .addParameter(ParameterSpec.builder("validationErrors", ParameterizedTypeName.get(List::class.asClassName(), ClassName(`package`,"ValidationError"))).build())
                            .build())
                    .build()
            )
            .addFunction(FunSpec.builder("Collection<ValidationError>.asChildrenOf")
                    .addParameter("parent", String::class)
                    .addCode("return this.map { it.copy(property = parent + \".\" + it.property) }")
                    .build()
            )
            .addFunction(FunSpec.builder("Validation.asChildrenOf")
                    .addParameter("parent", String::class)
                    .addCode("return when(this){ is Invalid -> this.validationErrors.asChildrenOf(parent); else -> null }")
                    .build()
            )
            .build()

}