package com.example.monitoring.domain;

public enum AssessmentCriteria {
    COMPETENCY("компетентность"), PERFORMANCE("результативность");
    private String text;

    AssessmentCriteria(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
    public static AssessmentCriteria fromString(String text) {
        for (AssessmentCriteria a : AssessmentCriteria.values()) {
            if (a.text.equalsIgnoreCase(text)) {
                return a;
            }
        }
        return null;
    }
}
