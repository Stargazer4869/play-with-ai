package functional;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class Compare {
    public static void main(String[] args) {
        List<Person> personList = Arrays.asList(new Person("Karen", 18), new Person("Mason", 26), new Person("Lincoln", 26));


        Comparator.comparing(Person::getAge).thenComparing(Person::getName);

        System.out.println(personList.stream()
                .sorted(Comparator.comparing(Person::getAge).thenComparing(Person::getName))
                .collect(Collectors.toList()));

//        Collections.sort();
    }
}