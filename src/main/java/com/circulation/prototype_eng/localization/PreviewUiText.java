package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/** Verbatim text from PM's implemented StructurePreviewUiScreen and materials section. */
public enum PreviewUiText implements LocalizationEnum {
    On("state.on"), Off("state.off"), ThreeD("mode.3d"), Slice("mode.slice"),
    Structure("struct_info.structure"), Scan("struct_info.scan"), Mode("struct_info.mode"), Layer("struct_info.slice_y"), Forming("struct_info.forming"),
    Reset("tooltip.reset_view"), Formed("tooltip.structural_forming"), Collapse("tooltip.component_switch"), Project("tooltip.place_projection"),
    Materials("tooltip.material_panel"), Wireframe("tooltip.wireframe"), LayerPreview("tooltip.layer_preview"), SliceY("tooltip.slice_y"),
    Empty("empty"), Requirement("requirement"), Status("status"), Count("count"), Remaining("remaining"), Required("required"), Key("key"),
    Replaceable("replaceable_hint"), NoSelection("no_selection.title"), SelectHint("no_selection.hint"),
    CurrentSelection("current_selection"), ChooseOne("anyof_choose_one"), Candidate("candidate"), ClickToSelect("click_to_select"), ReadOnly("read_only"),
    Match("entry_status.match"), Missing("entry_status.missing"), Wrong("entry_status.wrong"), Unloaded("entry_status.unloaded"), Unknown("entry_status.unknown");

    private final String translationKey;
    PreviewUiText(String suffix) { translationKey = "gui." + Tags.MOD_ID + ".preview." + suffix; }
    @Override public String getTranslationKey() { return translationKey; }
    public static String onOff(boolean value) { return (value ? On : Off).getLocal(); }
}
