package example;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class ExampleRequirements {
    public static final ResourceLocation MACHINE_GRADE = new ResourceLocation("example", "machine_grade");
    public static final RecipeRequirementType<MinimumGrade> GRADE = new RecipeRequirementType<>(
        new ResourceLocation("example", "minimum_grade"), new GradeSystem());

    public record MinimumGrade(int value) {
        public MinimumGrade {
            if (value < 1) throw new IllegalArgumentException("Minimum grade must be positive");
        }
    }

    private enum Text implements LocalizationEnum {
        GRADE_TOO_LOW;

        @Override
        public String getTranslationKey() {
            return "example.recipe.grade_too_low";
        }
    }

    private static final RequirementTransaction TOO_LOW = new RequirementTransaction() {
        @Override
        public ProcessResult result() {
            return new ProcessResult.Blocked(Text.GRADE_TOO_LOW.getTranslationKey());
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    };

    private static final class GradeSystem implements RecipeRequirementSystem<MinimumGrade> {
        @Override
        public RequirementTransaction start(RecipeProcess process, RecipeRequirement<MinimumGrade> requirement) {
            return process.attributes().value(MACHINE_GRADE, 0) >= requirement.payload().value()
                ? RequirementTransaction.SUCCESS : TOO_LOW;
        }

        @Override
        public @Nullable Object crossRecipeTickIo(MinimumGrade payload) {
            return null;
        }
    }
}
