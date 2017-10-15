/**
 * MegaMek - Copyright (C) 2004 Ben Mazur (bmazur@sev.org)
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */
/*
 * Created on Oct 15, 2017
 *
 */
package megamek.common.weapons;

import java.util.Vector;

import megamek.common.AmmoType;
import megamek.common.IGame;
import megamek.common.Report;
import megamek.common.ToHitData;
import megamek.common.actions.WeaponAttackAction;
import megamek.server.Server;

/**
 * @author Dave Nawton
 */
public class ACCaseLessHandler extends ACWeaponHandler {

    /**
     *
     */
    //private static final long serialVersionUID = ;

    /**
     * @param t
     * @param w
     * @param g
     */
    public ACCaseLessHandler (ToHitData t, WeaponAttackAction w,
            IGame g, Server s) {
        super(t, w, g, s);
    }

    /*
     * (non-Javadoc)
     * 
     * @see megamek.common.weapons.UltraWeaponHandler#doChecks(java.util.Vector)
     */
    @Override
    protected boolean doChecks(Vector<Report> vPhaseReport) {
        if ((roll <= 2)) {
            Report r = new Report();
            r.subject = subjectId;
            weapon.setJammed(true);
          
            if (wtype.getAmmoType() == AmmoType.M_CASELESS) {
                r.messageId = 3160;
                weapon.setHit(true);
            }
            vPhaseReport.addElement(r);
            return true;
        }
        return false;
    }
}
