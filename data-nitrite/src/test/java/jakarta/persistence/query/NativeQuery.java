package jakarta.persistence.query;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Stand-in for the Jakarta Persistence 4 annotation, which the 3.2 API on this classpath lacks.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface NativeQuery {
    String value();
}
