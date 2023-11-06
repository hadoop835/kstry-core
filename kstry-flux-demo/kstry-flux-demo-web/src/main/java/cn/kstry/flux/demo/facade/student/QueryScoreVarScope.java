package cn.kstry.flux.demo.facade.student;

import cn.kstry.flux.demo.dto.student.*;
import cn.kstry.framework.core.bus.ScopeData;
import cn.kstry.framework.core.enums.ScopeTypeEnum;
import lombok.Data;
import lombok.experimental.FieldNameConstants;

import java.util.List;

@Data
@FieldNameConstants(innerTypeName = "F")
public class QueryScoreVarScope implements ScopeData {

    private StudentBasic studentBasic;

    private StudentPrivacy studentPrivacy;

    private String student;

    private List<StudyExperience> studyExperienceList;

    private List<Long> classIds;

    private List<ClassInfo> classInfos;

    private List<ScoreInfo> scoreInfos;

    @Override
    public ScopeTypeEnum getScopeDataEnum() {
        return ScopeTypeEnum.VARIABLE;
    }
}
