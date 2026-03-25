package org.dean.experimental.functional;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public class ForEachLoop {
    public static void main(String[] args) {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        for (int i = 0; i < list.size(); i++) {
            System.out.println(list.get(i));
        }

        for (Integer i : list) {
            System.out.println(i);
        }

        list.forEach((Integer i) -> System.out.println(i));

        list.forEach((i) -> System.out.println(i));

        list.forEach(i -> System.out.println(i));

        list.forEach(System.out::println);

        list.stream()
                .map(e -> String.valueOf(e))
//                .map(String::valueOf)
                .forEach(System.out::println);

        list.stream()
//                .map(Integer::toString)
//                .map(e -> Integer.toString(e))
//                .map(String::toString)
                .forEach(System.out::println);

        list.stream()
                .reduce(0, Integer::sum);
//                .reduce(0, (a, b) -> Integer.sum(a, b));


        list.stream()
                .map(e -> e.toString())
                .reduce("", String::concat);
//                .reduce(0, (a, b) -> Integer.sum(a, b));

        int result = 0;
        for (Integer i : list) {
            if (i % 2 == 0) {
                result += i * 2;
            }
        }
        System.out.println(result);

        System.out.println(list.stream()
                .filter(i -> i % 2 == 0)
                .mapToInt(e -> e * 2)
//                .map(e -> e * 2)
                .sum());
//                .reduce(0, Integer::sum));
    }
}
