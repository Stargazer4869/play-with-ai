package org.dean.experimental.functional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class TestInfinite {
    public static void main(String[] args) {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        System.out.println(list.stream()
                .filter(e -> e > 3)
                .filter(PrimeCheck::isPrime)
                .findFirst());


        Stream.iterate(100, (e) -> {
            System.out.println("asd");
            return e + 1;
        })
                .filter(PrimeCheck::isPrime)
                .mapToInt(e -> e * 2)
                .limit(3)
                .sum();
    }
}
