package com.eia.contract.expert.bind;

/** One bind precondition and its outcome (SPEC 11.3.8 step 1, `contract.bind-eligible`). */
public record BindCheck(String code, boolean passed, String detail) {

    public static BindCheck pass(String code, String detail) {
        return new BindCheck(code, true, detail);
    }

    public static BindCheck fail(String code, String detail) {
        return new BindCheck(code, false, detail);
    }
}
