package com.example.monitoring.domain;

public enum AssessmentValue {
    LOWER("ниже"), MEET("соответствует"), HIGHER("выше");
    private String text;

    AssessmentValue(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
    public static AssessmentValue fromString(String text) {
        for (AssessmentValue a : AssessmentValue.values()) {
            if (a.text.equalsIgnoreCase(text)) {
                return a;
            }
        }
        return null;
    }
}
