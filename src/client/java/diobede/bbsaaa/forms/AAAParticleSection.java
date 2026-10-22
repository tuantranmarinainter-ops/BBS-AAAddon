package diobede.bbsaaa.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.FormSection;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;

import java.util.List;

/**
 * Form palette section that lists the AAA particle form.
 */
public class AAAParticleSection extends FormSection
{
    private List<FormCategory> categories = List.of();

    public AAAParticleSection(FormCategories parent)
    {
        super(parent);
    }

    /* BBS FS FormSection has no getTitle(); kept as a helper for the category title. */
    public IKey getTitle()
    {
        return L10n.lang("bbsaaa.ui.forms.categories.aaa_particles");
    }

    @Override
    public void initiate()
    {
        FormCategory category = new FormCategory(this.getTitle(), new ValueBoolean("bbs-aaaddon_aaa_particles", true));

        category.addForm(BBSMod.getForms().create(Link.bbs("aaa_particle")));

        this.categories = List.of(category);
    }

    @Override
    public List<FormCategory> getCategories()
    {
        return this.categories;
    }
}
