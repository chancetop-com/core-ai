package ai.core.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/2/6
 * description:
 */
class JsonUTest {
    @Test
    void test() {
        var js = JsonUtil.toJson(List.of(new Person("li", "18"), new Person("chen", "19")));
        List<Person> personList = JsonUtil.fromJson(new TypeReference<>() {
        }, js);
        assert !personList.isEmpty();

    }

    public static class Person {
        public String name;
        public String age;

        Person() {
        }

        Person(String name, String age) {
            this.name = name;
            this.age = age;
        }


    }
}
