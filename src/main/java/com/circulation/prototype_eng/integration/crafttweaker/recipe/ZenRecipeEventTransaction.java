package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.EventTransaction")
public record ZenRecipeEventTransaction(RequirementTransaction value) {
    public ZenRecipeEventTransaction {
        Objects.requireNonNull(value, "value");
    }

    @ZenMethod
    public static ZenRecipeEventTransaction success() {
        return new ZenRecipeEventTransaction(RequirementTransaction.SUCCESS);
    }

    @ZenMethod
    public static ZenRecipeEventTransaction blocked(String reason) {
        return transaction(new ProcessResult.Blocked(reason), () -> {}, () -> {});
    }

    @ZenMethod
    public static ZenRecipeEventTransaction failure(String reason) {
        return transaction(new ProcessResult.Failure(reason), () -> {}, () -> {});
    }

    /** Rollback must undo acquisition and commit effects, including a partially throwing commit. */
    @ZenMethod
    public static ZenRecipeEventTransaction create(ZenRecipeEventAction commit, ZenRecipeEventAction rollback) {
        return transaction(ProcessResult.SUCCESS, commit, rollback);
    }

    private static ZenRecipeEventTransaction transaction(ProcessResult result, ZenRecipeEventAction commit,
                                                         ZenRecipeEventAction rollback) {
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(rollback, "rollback");
        return new ZenRecipeEventTransaction(new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return result;
            }

            @Override
            public void commit() {
                commit.run();
            }

            @Override
            public void rollback() {
                rollback.run();
            }
        });
    }

}
