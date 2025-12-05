package ai.core.chat;

import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import core.framework.api.json.Property;

/**
 * author: lim chen
 * date: 2025/12/5
 * description:
 */
public class FakerTools {

    @CoreAiMethod(description = "query_person", name = "query_person")
    public Person queryPerson(@CoreAiParameter(name = "name", description = "") String name) {
        return new Person(16, name);
    }

    public static class Person {
        @Property(name = "name_n")
        public String name;
        @Property(name = "age_n")
        public Integer age;

        public Person(Integer age, String name) {
            this.age = age;
            this.name = name;
        }


    }
}
