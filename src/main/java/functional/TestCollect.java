package functional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TestCollect {
    public static void main(String[] args) {
        List<Person> personList = Arrays.asList(new Person("Karen", 18), new Person("Mason", 26), new Person("Lincoln", 26),
                new Person("Karen", 9), new Person("Mason", 13), new Person("Lincoln", 13));

//        System.out.println(personList.stream().filter(p -> p.getAge() < 20)
//                .collect(Collectors.toList()));

        System.out.println(personList.stream()
                .collect(Collectors.groupingBy(Person::getName)));

        System.out.println(personList.stream()
                .collect(Collectors.groupingBy(Person::getName, Collectors.summingInt(Person::getAge))));
    }
}
