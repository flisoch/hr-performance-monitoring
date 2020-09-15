package com.example.monitoring.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Assessment {
    private Performer performer;
    private Team team;
    private AssessmentCriteria assessmentCriteria;
    private AssessmentValue assessmentValue;
    private String comment;

    private static HashMap<String, Integer> sheetMapping =
            generateSheetMap();

    private static HashMap<String, Integer> generateSheetMap() {
        HashMap<String, Integer> sheetMapping = new HashMap<>();
        sheetMapping.put("performer", 0);
        sheetMapping.put("team", 1);
        sheetMapping.put("assessmentCriteria", 2);
        sheetMapping.put("assessmentValue", 3);
        sheetMapping.put("comment", 4);
        return sheetMapping;
    }

    public static Assessment buildFromSheetRow(List<Object> row) {
        String performerName = (String) row.get(sheetMapping.get("performer"));
        String assessmentCriteria = (String) row.get((sheetMapping.get("assessmentCriteria")));
        String assessmentValue = (String) row.get(sheetMapping.get("assessmentValue"));
        String teamName = (String) row.get(sheetMapping.get("team"));
        String comment = "";

        if (row.size() == 6) {
            comment = (String) row.get(sheetMapping.get("comment"));
        }
        return Assessment.builder()
                .performer(Performer.builder().name(performerName).build())
                .assessmentCriteria(AssessmentCriteria.fromString(assessmentCriteria))
                .assessmentValue(AssessmentValue.fromString(assessmentValue))
                .team(Team.builder().name(teamName).build())
                .comment(comment)
                .build();
    }

    public static Assessment average(Assessment pmAssessment, Assessment tLeadAssessment) {
        AssessmentValue pm = pmAssessment.getAssessmentValue();
        AssessmentValue tl = tLeadAssessment.getAssessmentValue();
        AssessmentValue avg;
        if ((pm.equals(AssessmentValue.LOWER) || tl.equals(AssessmentValue.LOWER)) &&
                (!pm.equals(AssessmentValue.HIGHER) && !tl.equals(AssessmentValue.HIGHER))
        ) {
            avg = AssessmentValue.LOWER;
        } else if (pm.equals(AssessmentValue.HIGHER) && tl.equals(AssessmentValue.HIGHER)) {
            avg = AssessmentValue.HIGHER;
        } else {
            avg = AssessmentValue.MEET;
        }
        return Assessment.builder()
                .assessmentCriteria(pmAssessment.getAssessmentCriteria())
                .assessmentValue(avg)
                .team(pmAssessment.getTeam())
                .performer(pmAssessment.performer)
                .build();
    }
}
