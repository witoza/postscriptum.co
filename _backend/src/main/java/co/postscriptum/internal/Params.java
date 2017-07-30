package co.postscriptum.internal;

import co.postscriptum.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class Params {

    private final Map<String, Object> params;

    private Params(Map<String, Object> params) {
        this.params = params;

        StringBuilder sb = new StringBuilder();
        Utils.toSafeString(sb, params);

        log.info("params={}", sb.toString());
    }

    public static Params of(Map<String, Object> params) {
        return new Params(params);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String name) {
        return (T) params.get(name);
    }

    public <T> T require(String name) {
        T value = get(name);
        if (value == null) {
            throw new BadRequestException("missing required param '" + name + "'");
        }
        return value;
    }

}