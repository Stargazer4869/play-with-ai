package functional.sharing;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

public class Sample {
    public static void main(String[] args) throws IOException {
        Resource.use("AA", (r) -> {
            System.out.println(r);
        });
    }
}

class Resource implements Closeable {
    private Resource(String s) {
    }

    public static void use(String s, Consumer<Resource> consume) throws IOException {
        try (Resource resource = new Resource(s)) {
            consume.accept(resource);
        }
    }

    @Override
    public void close() throws IOException {

    }
}