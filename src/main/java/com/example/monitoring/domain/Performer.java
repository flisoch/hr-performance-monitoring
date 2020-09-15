package com.example.monitoring.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Performer {
    private String name;
    private Assessment efficiencyAssessments;
    private Assessment competencyAssessment;

    public static Performer buildFromSheetRow(List<Object> row) {
        return Performer.builder()
                .name((String) row.get(0))
                .build();
    }
}
