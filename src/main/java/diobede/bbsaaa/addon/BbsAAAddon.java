package diobede.bbsaaa.addon;

import diobede.bbsaaa.BBSAAaddon;
import diobede.bbsaaa.forms.AAAParticleForm;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterFormsEvent;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.Link;

public class BbsAAAddon implements BBSAddonMod
{
    @Subscribe
    public void forms(RegisterFormsEvent event)
    {
        event.forms.register(Link.bbs("aaa_particle"), AAAParticleForm.class);
    }

    @Subscribe
    public void resources(RegisterSourcePacksEvent event)
    {
        event.registerAddon(BBSAAaddon.MOD_ID, BbsAAAddon.class);
    }
}
