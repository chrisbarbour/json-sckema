# JSON SCKEMA
Generation of kotlin data classes from Json-schema

# Get Started

## Using Maven
Using Maven you can generate kotlin data classes using the `maven-exec-plugin`.
The following is an example of how you can generate classes from definitions within a swagger definition file.

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>1.4.0</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals>
                <goal>java</goal>
            </goals>
            <configuration>
                <mainClass>sckema.SchemaMapperKt</mainClass>
                <arguments>
                    <argument>${project.build.directory}/generated-sources/</argument>
                    <argument>product</argument>
                    <argument>yaml</argument>
                    <argument>${project.basedir}/src/main/resources/swagger.yml</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```