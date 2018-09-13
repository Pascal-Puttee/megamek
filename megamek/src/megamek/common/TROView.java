/**
 * 
 */
package megamek.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import megamek.client.ui.Messages;
import megamek.common.annotations.Nullable;
import megamek.common.logging.DefaultMmLogger;
import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.Quirks;
import megamek.common.util.MegaMekFile;
import megamek.common.verifier.BayData;
import megamek.common.verifier.EntityVerifier;
import megamek.common.verifier.TestAero;
import megamek.common.verifier.TestBattleArmor;
import megamek.common.verifier.TestMech;
import megamek.common.verifier.TestTank;
import megamek.common.weapons.InfantryAttack;

/**
 * Fills in a template to produce a unit summary in TRO format.
 * 
 * @author Neoancient
 *
 */
public class TROView {
	
	private Template template;
	private Map<String, Object> model = new HashMap<>();
    private EntityVerifier verifier = EntityVerifier.getInstance(new MegaMekFile(
            Configuration.unitsDir(), EntityVerifier.CONFIG_FILENAME).getFile());
    
    private boolean includeFluff = true;

	public TROView(Entity entity, boolean html) {
		String templateFileName = null;
		if (entity.hasETypeFlag(Entity.ETYPE_MECH)) {
			templateFileName = "mech";
			addMechData((Mech) entity);
		} else if (entity.hasETypeFlag(Entity.ETYPE_TANK)) {
			templateFileName = "vehicle";
			addVehicleData((Tank) entity);
		} else if (entity.hasETypeFlag(Entity.ETYPE_AERO)) {
			templateFileName = "aero";
			addAeroData((Aero) entity);
		} else if (entity.hasETypeFlag(Entity.ETYPE_BATTLEARMOR)) {
			templateFileName = "ba";
			addBattleArmorData((BattleArmor) entity);
		}
		if (null != templateFileName) {
			templateFileName = "tro/" + templateFileName + ".ftl";
			if (html) {
				templateFileName += "h";
			}
			try {
				template = TemplateConfiguration.getInstance().getTemplate(templateFileName);
			} catch (IOException e) {
				DefaultMmLogger.getInstance().error(getClass(), "TROView(Entity)", e);
			}
		}
	}
	
	/**
	 * Uses the template and supplied {@link Entity} to generate a TRO document
	 * 
	 * @return The generated document. Returns {@code null} if there was an error that prevented
	 *         the document from being generated. Check logs for reason.
	 */
	@Nullable public String processTemplate() {
		if (null != template) {
			model.put("includeFluff", includeFluff);
			
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			Writer out = new OutputStreamWriter(os);
			try {
				template.process(model, out);
				
			} catch (TemplateException | IOException e) {
				DefaultMmLogger.getInstance().error(getClass(), "processTemplate()", e);
				e.printStackTrace();
			}
			return os.toString();
		}
		return null;
	}
	
	private void addBasicData(Entity entity) {
		model.put("formatBasicDataRow", new FormatTableRowMethod(new int[] { 30, 20, 5},
				new Justification[] { Justification.LEFT, Justification.LEFT, Justification.RIGHT }));
		model.put("fullName", entity.getShortNameRaw());
		model.put("chassis", entity.getChassis());
		model.put("techBase", formatTechBase(entity));
		model.put("tonnage", NumberFormat.getInstance().format(entity.getWeight()));
		model.put("battleValue", NumberFormat.getInstance().format(entity.calculateBattleValue()));
		
        StringJoiner quirksList = new StringJoiner(", ");
        Quirks quirks = entity.getQuirks();
        for (Enumeration<IOptionGroup> optionGroups = quirks.getGroups(); optionGroups.hasMoreElements();) {
            IOptionGroup group = optionGroups.nextElement();
            if (quirks.count(group.getKey()) > 0) {
                for (Enumeration<IOption> options = group.getOptions(); options.hasMoreElements();) {
                    IOption option = options.nextElement();
                    if (option != null && option.booleanValue()) {
                        quirksList.add(option.getDisplayableNameWithValue());
                    }
                }
            }
        }
        if (quirksList.length() > 0) {
        	model.put("quirks", quirksList.toString());
        }
        
	}

	private void addMechData(Mech mech) {
		model.put("formatArmorRow", new FormatTableRowMethod(new int[] { 20, 10, 10},
				new Justification[] { Justification.LEFT, Justification.CENTER, Justification.CENTER }));
		addBasicData(mech);
		addArmorAndStructure(mech);
		int nameWidth = addEquipment(mech);
		model.put("formatEquipmentRow", new FormatTableRowMethod(new int[] { nameWidth, 12, 8, 10, 8},
				new Justification[] { Justification.LEFT, Justification.CENTER, Justification.CENTER,
						Justification.CENTER, Justification.CENTER}));
		addMechFluff(mech);
		mech.setConversionMode(0);
		model.put("isOmni", mech.isOmni());
		model.put("isQuad", mech.hasETypeFlag(Entity.ETYPE_QUAD_MECH));
		model.put("isTripod", mech.hasETypeFlag(Entity.ETYPE_TRIPOD_MECH));
		TestMech testMech = new TestMech(mech, verifier.mechOption, null);
		model.put("structureName", mech.getStructureType() == EquipmentType.T_STRUCTURE_STANDARD?
				"" : EquipmentType.getStructureTypeName(mech.getStructureType()));
		model.put("isMass", NumberFormat.getInstance().format(testMech.getWeightStructure()));
		model.put("engineName", stripNotes(mech.getEngine().getEngineName()));
		model.put("engineMass", NumberFormat.getInstance().format(testMech.getWeightEngine()));
		model.put("walkMP", mech.getWalkMP());
		model.put("runMP", mech.getRunMPasString());
		model.put("jumpMP", mech.getJumpMP());
		model.put("hsType", mech.getHeatSinkTypeName());
		model.put("hsCount", mech.hasDoubleHeatSinks()?
				mech.heatSinks() + " [" + (mech.heatSinks() * 2) + "]" : mech.heatSinks());
		model.put("hsMass", NumberFormat.getInstance().format(testMech.getWeightHeatSinks()));
		if (mech.getGyroType() == Mech.GYRO_STANDARD) {
			model.put("gyroType", mech.getRawSystemName(Mech.SYSTEM_GYRO));
		} else {
			model.put("gyroType", Mech.getGyroDisplayString(mech.getGyroType())); 
		}
		model.put("gyroMass", NumberFormat.getInstance().format(testMech.getWeightGyro()));
		if ((mech.getCockpitType() == Mech.COCKPIT_STANDARD)
				|| (mech.getCockpitType() == Mech.COCKPIT_INDUSTRIAL)) {
			model.put("cockpitType", mech.getRawSystemName(Mech.SYSTEM_COCKPIT));
		} else {
			model.put("cockpitType", Mech.getCockpitDisplayString(mech.getCockpitType()));
		}
		model.put("cockpitMass", NumberFormat.getInstance().format(testMech.getWeightCockpit()));
		String atName = formatArmorType(mech, true);
		if (atName.length() > 0) {
			model.put("armorType", " (" + atName + ")");
		} else {
			model.put("armorType", "");
		}
		model.put("armorFactor", mech.getTotalOArmor());
		model.put("armorMass", NumberFormat.getInstance().format(testMech.getWeightArmor()));
		if (mech.isOmni()) {
			addFixedOmni(mech);
		}
		if (mech.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH)) {
			final LandAirMech lam = (LandAirMech) mech;
			model.put("lamConversionMass", testMech.getWeightMisc());
			if (lam.getLAMType() == LandAirMech.LAM_STANDARD) {
				model.put("airmechCruise", lam.getAirMechCruiseMP());
				model.put("airmechFlank", lam.getAirMechFlankMP());
			} else {
				model.put("airmechCruise", "N/A");
				model.put("airmechFlank", "N/A");
			}
			lam.setConversionMode(LandAirMech.CONV_MODE_FIGHTER);
			model.put("safeThrust", lam.getWalkMP());
			model.put("maxThrust", lam.getRunMP());
		} else if (mech.hasETypeFlag(Entity.ETYPE_QUADVEE)) {
			final QuadVee qv = (QuadVee) mech;
			qv.setConversionMode(QuadVee.CONV_MODE_VEHICLE);
			model.put("qvConversionMass", testMech.getWeightMisc());
			model.put("qvType", Messages.getString("MovementType." + qv.getMovementModeAsString()));
			model.put("qvCruise", qv.getWalkMP());
			model.put("qvFlank", qv.getRunMPasString());
		}
		model.put("rightArmActuators", countArmActuators(mech, Mech.LOC_RARM));
		model.put("leftArmActuators", countArmActuators(mech, Mech.LOC_LARM));
	}
	
	private String countArmActuators(Mech mech, int location) {
		StringJoiner sj = new StringJoiner(", ");
		for (int act = Mech.ACTUATOR_SHOULDER; act <= Mech.ACTUATOR_HAND; act++) {
			if (mech.hasSystem(act, location)) {
				sj.add(mech.getRawSystemName(act));
			}
		}
		return sj.toString();
	}
	
	@SuppressWarnings("unchecked")
	private void addVehicleData(Tank tank) {
		model.put("formatArmorRow", new FormatTableRowMethod(new int[] { 20, 10, 10},
				new Justification[] { Justification.LEFT, Justification.CENTER, Justification.CENTER }));
		addBasicData(tank);
		addArmorAndStructure(tank);
		int nameWidth = addEquipment(tank);
		model.put("formatEquipmentRow", new FormatTableRowMethod(new int[] { nameWidth, 12, 12},
				new Justification[] { Justification.LEFT, Justification.CENTER, Justification.CENTER,
						Justification.CENTER, Justification.CENTER}));
		addVehicleFluff(tank);
		model.put("isOmni", tank.isOmni());
		model.put("isVTOL", tank.hasETypeFlag(Entity.ETYPE_VTOL));
		model.put("isSuperheavy", tank.isSuperHeavy());
		model.put("isSupport", tank.isSupportVehicle());
		model.put("hasTurret", !tank.hasNoTurret());
		model.put("hasTurret2", !tank.hasNoDualTurret());
		model.put("moveType", Messages.getString("MovementType." + tank.getMovementModeAsString()));
		TestTank testTank = new TestTank(tank, verifier.tankOption, null);
		model.put("isMass", NumberFormat.getInstance().format(testTank.getWeightStructure()));
		model.put("engineName", stripNotes(tank.getEngine().getEngineName()));
		model.put("engineMass", NumberFormat.getInstance().format(testTank.getWeightEngine()));
		model.put("walkMP", tank.getWalkMP());
		model.put("runMP", tank.getRunMPasString());
		if (tank.getJumpMP() > 0) {
			model.put("jumpMP", tank.getJumpMP());
		}
		model.put("hsCount", Math.max(testTank.getCountHeatSinks(),
				tank.getEngine().getWeightFreeEngineHeatSinks()));
		model.put("hsMass", NumberFormat.getInstance().format(testTank.getWeightHeatSinks()));
		model.put("controlMass", testTank.getWeightControls());
		model.put("liftMass", testTank.getTankWeightLifting());
		model.put("amplifierMass", testTank.getWeightPowerAmp());
		model.put("turretMass", testTank.getTankWeightTurret());
		model.put("turretMass2", testTank.getTankWeightDualTurret());
		String atName = formatArmorType(tank, true);
		if (atName.length() > 0) {
			model.put("armorType", " (" + atName + ")");
		} else {
			model.put("armorType", "");
		}
		model.put("armorFactor", tank.getTotalOArmor());
		model.put("armorMass", NumberFormat.getInstance().format(testTank.getWeightArmor()));
		if (tank.isOmni()) {
			addFixedOmni(tank);
		}
		for (Transporter t : tank.getTransports()) {
			Map<String, Object> row = this.formatTransporter(t, tank.getLocationName(Tank.LOC_BODY));
			if (null == row) {
				continue;
			}
			if (tank.isOmni() && !tank.isPodMountedTransport(t)) {
				((List<Map<String, Object>>) model.get("fixedEquipment")).add(row);
				model.merge("fixedTonnage", row.get("tonnage"), (o1, o2) -> ((double) o1) + ((double) o2));
			} else {
				((List<Map<String, Object>>) model.get("equipment")).add(row);
			}
		}
	}
	
	private void addAeroData(Aero aero) {
		model.put("formatArmorRow", new FormatTableRowMethod(new int[] { 20, 10},
				new Justification[] { Justification.LEFT, Justification.CENTER }));
		addBasicData(aero);
		addArmorAndStructure(aero);
		int nameWidth = addEquipment(aero);
		model.put("formatEquipmentRow", new FormatTableRowMethod(new int[] { nameWidth, 12, 8, 8},
				new Justification[] { Justification.LEFT, Justification.CENTER, Justification.CENTER,
						Justification.CENTER}));
		addAeroFluff(aero);
		model.put("isOmni", aero.isOmni());
		model.put("isConventional", aero.hasETypeFlag(Entity.ETYPE_CONV_FIGHTER));
		TestAero testAero = new TestAero(aero, verifier.aeroOption, null);
		model.put("engineName", stripNotes(aero.getEngine().getEngineName()));
		model.put("engineMass", NumberFormat.getInstance().format(testAero.getWeightEngine()));
		model.put("safeThrust", aero.getWalkMP());
		model.put("maxThrust", aero.getRunMP());
		model.put("si", aero.get0SI());
		model.put("hsCount", aero.getHeatType() == Aero.HEAT_DOUBLE?
				aero.getOHeatSinks() + " [" + (aero.getOHeatSinks() * 2) + "]" : aero.getOHeatSinks());
		model.put("fuelPoints", aero.getFuel());
		model.put("fuelMass", aero.getFuelTonnage());
		model.put("hsMass", NumberFormat.getInstance().format(testAero.getWeightHeatSinks()));
		if (aero.getCockpitType() == Aero.COCKPIT_STANDARD) {
			model.put("cockpitType", "Cockpit");
		} else {
			model.put("cockpitType", Aero.getCockpitTypeString(aero.getCockpitType()));
		}
		model.put("cockpitMass", NumberFormat.getInstance().format(testAero.getWeightControls()));
		String atName = formatArmorType(aero, true);
		if (atName.length() > 0) {
			model.put("armorType", " (" + atName + ")");
		} else {
			model.put("armorType", "");
		}
		model.put("armorFactor", aero.getTotalOArmor());
		model.put("armorMass", NumberFormat.getInstance().format(testAero.getWeightArmor()));
		if (aero.isOmni()) {
			addFixedOmni(aero);
		}
	}
	
	private void addBattleArmorData(BattleArmor ba) {
		addBasicData(ba);
		TestBattleArmor testBA = new TestBattleArmor(ba, verifier.baOption, null);
		if (ba.getChassisType() == BattleArmor.CHASSIS_TYPE_QUAD) {
			model.put("chassisType", Messages.getString("TROView.chassisQuad"));
		} else {
			model.put("chassisType", Messages.getString("TROView.chassisBiped"));
		}
		model.put("weightClass", EntityWeightClass
				.getClassName(EntityWeightClass.getWeightClass(ba.getTrooperWeight(), ba)));
		model.put("weight", ba.getTrooperWeight() * 1000);
		model.put("swarmAttack", ba.canMakeAntiMekAttacks()? "Yes" : "No");
		// We need to allow it for UMU that otherwise qualifies
		model.put("legAttack", (ba.canDoMechanizedBA()
                && (ba.getWeightClass() < EntityWeightClass.WEIGHT_HEAVY))? "Yes" : "No");
		model.put("mechanized", ba.canDoMechanizedBA()? "Yes" : "No");
		model.put("antiPersonnel", ba.getEquipment().stream().anyMatch(m -> m.isAPMMounted())? "Yes" : "No");
		
		model.put("massChassis", testBA.getWeightChassis() * 1000);
		model.put("groundMP", ba.getWalkMP());
		model.put("groundMass", testBA.getWeightGroundMP() * 1000);
		if (ba.getMovementMode() == EntityMovementMode.VTOL) {
			model.put("vtolMP", ba.getOriginalJumpMP());
			model.put("vtolMass", testBA.getWeightSecondaryMotiveSystem() * 1000);
		} else if (ba.getMovementMode() == EntityMovementMode.INF_UMU) {
			model.put("umuMP", ba.getOriginalJumpMP());
			model.put("umuMass", testBA.getWeightSecondaryMotiveSystem() * 1000);
		} else {
			model.put("jumpMP", ba.getOriginalJumpMP());
			model.put("jumpMass", testBA.getWeightSecondaryMotiveSystem() * 1000);
		}
		List<Map<String, Object>> manipulators = new ArrayList<>();
		manipulators.add(formatManipulatorRow(BattleArmor.MOUNT_LOC_LARM, ba.getLeftManipulator()));
		manipulators.add(formatManipulatorRow(BattleArmor.MOUNT_LOC_RARM, ba.getRightManipulator()));
		model.put("manipulators", manipulators);
		model.put("armorType", EquipmentType.getArmorTypeName(ba.getArmorType(BattleArmor.LOC_TROOPER_1))
				.replaceAll("^BA\\s+", ""));
		model.put("armorMass", testBA.getWeightArmor() * 1000);
		model.put("armorValue", ba.getOArmor(BattleArmor.LOC_TROOPER_1));
		model.put("internal", ba.getOInternal(BattleArmor.LOC_TROOPER_1));
		addBAEquipment(ba);
		if (ba.getEquipment().stream().anyMatch(m -> m.getBaMountLoc() == BattleArmor.MOUNT_LOC_TURRET)) {
			Map<String, Object> modularMount = new HashMap<>();
			modularMount.put("name", ba.hasModularTurretMount()?
					Messages.getString("TROView.BAModularTurret"):
					Messages.getString("TROView.BATurret"));
			modularMount.put("location", BattleArmor.getBaMountLocAbbr(BattleArmor.MOUNT_LOC_TURRET));
			int turretSlots = ba.getTurretCapacity();
			if (ba.hasModularTurretMount()) {
				turretSlots += 2;
			}
			modularMount.put("slots", turretSlots + " (" + ba.getTurretCapacity() + ")");
			modularMount.put("mass", testBA.getWeightTurret() * 1000);
			model.put("modularMount", modularMount);
		}
	}
	
	private Map<String, Object> formatManipulatorRow(int mountLoc, Mounted manipulator) {
		Map<String, Object> retVal = new HashMap<>();
		retVal.put("locName", BattleArmor.getBaMountLocAbbr(mountLoc));
		if (null == manipulator) {
			retVal.put("eqName", Messages.getString("TROView.None"));
			retVal.put("eqMass", 0);
		} else {
			String name = manipulator.getName();
			if (name.contains("[")) {
				name = name.replaceAll(".*\\[", "").replaceAll("\\].*", "");
			}
			retVal.put("eqName", name);
			retVal.put("eqMass", manipulator.getType().getTonnage(null) * 1000);
		}
		return retVal;
	}
	
	private void addEntityFluff(Entity entity) {
		model.put("year", String.valueOf(entity.getYear()));
		model.put("cost", NumberFormat.getInstance().format(entity.getCost(false)));
		model.put("techRating", entity.getFullRatingName());
		if (entity.getFluff().getOverview().length() > 0) {
			model.put("fluffOverview", entity.getFluff().getOverview());
		}
		if (entity.getFluff().getCapabilities().length() > 0) {
			model.put("fluffCapabilities", entity.getFluff().getCapabilities());
		}
		if (entity.getFluff().getDeployment().length() > 0) {
			model.put("fluffDeployment", entity.getFluff().getDeployment());
		}
		if (entity.getFluff().getHistory().length() > 0) {
			model.put("fluffHistory", entity.getFluff().getHistory());
		}
	}
	
	private void addMechVeeAeroFluff(Entity entity) {
		addEntityFluff(entity);
		model.put("massDesc", (int) entity.getWeight()
				+ Messages.getString("TROView.tons"));
		// Prefix engine manufacturer
		model.put("engineDesc", stripNotes(entity.getEngine().getEngineName()));
		model.put("cruisingSpeed", entity.getWalkMP() * 10.8);
		model.put("maxSpeed", entity.getRunMP() * 10.8);
		model.put("armorDesc", formatArmorType(entity, false));
		Map<String, Integer> weaponCount = new HashMap<>();
		double podSpace = 0.0;
		for (Mounted m : entity.getEquipment()) {
			if (m.isOmniPodMounted()) {
				podSpace += m.getType().getTonnage(entity, m.getLocation());
			} else if (m.getType() instanceof WeaponType) {
				weaponCount.merge(m.getType().getName(), 1, Integer::sum);
			}
		}
		List<String> armaments = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : weaponCount.entrySet()) {
			armaments.add(String.format("%d %s", entry.getValue(), entry.getKey()));
		}
		if (podSpace > 0) {
			armaments.add(String.format(Messages.getString("TROView.podspace.format"), podSpace));
		}
		model.put("armamentList", armaments);
	}
	
	private void addMechFluff(Mech mech) {
		addMechVeeAeroFluff(mech);
		// If we had a fluff field for chassis type we would put it here
		String chassisDesc = EquipmentType.getStructureTypeName(mech.getStructureType());
		if (mech.isIndustrial()) {
			chassisDesc += Messages.getString("TROView.chassisIndustrial");
		}
		if (mech.isSuperHeavy()) {
			chassisDesc += Messages.getString("TROView.chassisSuperheavy");
		}
		if (mech.hasETypeFlag(Entity.ETYPE_QUADVEE)) {
			chassisDesc += Messages.getString("TROView.chassisQuadVee");
		} else if (mech.hasETypeFlag(Entity.ETYPE_QUAD_MECH)) {
			chassisDesc += Messages.getString("TROView.chassisQuad");
		} else if (mech.hasETypeFlag(Entity.ETYPE_TRIPOD_MECH)) {
			chassisDesc += Messages.getString("TROView.chassisTripod");
		} else if (mech.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH)) {
			chassisDesc += Messages.getString("TROView.chassisLAM");
		} else {
			chassisDesc += Messages.getString("TROView.chassisBiped");
		}
		model.put("chassisDesc", chassisDesc);
		model.put("jjDesc", formatJJDesc(mech));
		model.put("jumpCapacity", mech.getJumpMP() * 30);
	}
	
	private void addVehicleFluff(Tank tank) {
		addMechVeeAeroFluff(tank);
		if (tank.getJumpMP() > 0) {
			model.put("jjDesc", Messages.getString("TROView.jjVehicle"));
			model.put("jumpCapacity", tank.getJumpMP() * 30);
		}
	}
	
	private void addAeroFluff(Aero aero) {
		addMechVeeAeroFluff(aero);
		// Add fluff frame description
	}

	private String formatTechBase(Entity entity) {
		StringBuilder sb = new StringBuilder();
		if (entity.isMixedTech()) {
			sb.append(Messages.getString("TROView.Mixed"));
		} else if (entity.isClan()) {
			sb.append(Messages.getString("TROView.Clan"));
		} else {
			sb.append(Messages.getString("TROView.InnerSphere"));
		}
		sb.append(" (").append(entity.getStaticTechLevel().toString()).append(")");
		return sb.toString();
	}
	
	private String formatArmorType(Entity entity, boolean trim) {
		if (entity.hasPatchworkArmor()) {
			return EquipmentType.getArmorTypeName(EquipmentType.T_ARMOR_PATCHWORK);
		}
		// Some types do not have armor on the first location, and others have only a single location
		int at = entity.getArmorType(Math.min(1, entity.locations() - 1));
		if (trim && (at == EquipmentType.T_ARMOR_STANDARD)) {
			return "";
		}
		String name = EquipmentType.getArmorTypeName(at);
		if (trim) {
			name = name.replace("-Fibrous", "").replace("-Aluminum", "");
		}
		return name;
	}

	private String formatArmorType(int at, boolean trim) {
		// Some types do not have armor on the first location, and others have only a single location
		if (trim && (at == EquipmentType.T_ARMOR_STANDARD)) {
			return "";
		}
		String name = EquipmentType.getArmorTypeName(at);
		if (trim) {
			name = name.replace("-Fibrous", "").replace("-Aluminum", "");
		}
		return name;
	}

	private static final int[][] MECH_ARMOR_LOCS = {
			{Mech.LOC_HEAD}, {Mech.LOC_CT}, {Mech.LOC_RT, Mech.LOC_LT},
			{Mech.LOC_RARM, Mech.LOC_LARM}, {Mech.LOC_RLEG, Mech.LOC_CLEG, Mech.LOC_LLEG}
	};
	
	private static final int[][] MECH_ARMOR_LOCS_REAR = {
			{Mech.LOC_CT}, {Mech.LOC_RT, Mech.LOC_LT}
	};
	
	private static final int[][] TANK_ARMOR_LOCS = {
			{Tank.LOC_FRONT}, {Tank.LOC_RIGHT, Tank.LOC_LEFT}, {Tank.LOC_REAR},
			{Tank.LOC_TURRET}, {Tank.LOC_TURRET_2}, {VTOL.LOC_ROTOR}
	};
	
	private static final int[][] SH_TANK_ARMOR_LOCS = {
			{SuperHeavyTank.LOC_FRONT},
			{SuperHeavyTank.LOC_FRONTRIGHT, SuperHeavyTank.LOC_FRONTLEFT},
			{SuperHeavyTank.LOC_REARRIGHT, SuperHeavyTank.LOC_REARLEFT},
			{SuperHeavyTank.LOC_REAR},
			{SuperHeavyTank.LOC_TURRET}, {SuperHeavyTank.LOC_TURRET_2}
	};
	
	private static final int[][] AERO_ARMOR_LOCS = {
			{Aero.LOC_NOSE}, {Aero.LOC_RWING, Aero.LOC_LWING}, {Aero.LOC_AFT}
	};
	
	private void addArmorAndStructure(Mech mech) {
		model.put("structureValues", addArmorStructureEntries(mech,
				(en, loc) -> en.getOInternal(loc),
				MECH_ARMOR_LOCS));
		model.put("armorValues", addArmorStructureEntries(mech,
				(en, loc) -> en.getOArmor(loc),
				MECH_ARMOR_LOCS));
		model.put("rearArmorValues", addArmorStructureEntries(mech,
				(en, loc) -> en.getOArmor(loc, true),
				MECH_ARMOR_LOCS_REAR));
		if (mech.hasPatchworkArmor()) {
			model.put("patchworkByLoc", addPatchworkATs(mech, MECH_ARMOR_LOCS));
		}
	}
	
	private void addArmorAndStructure(Tank tank) {
		if (tank.hasETypeFlag(Entity.ETYPE_SUPER_HEAVY_TANK)) {
			model.put("structureValues", addArmorStructureEntries(tank,
					(en, loc) -> en.getOInternal(loc),
					SH_TANK_ARMOR_LOCS));
			model.put("armorValues", addArmorStructureEntries(tank,
					(en, loc) -> en.getOArmor(loc),
					SH_TANK_ARMOR_LOCS));
			if (tank.hasPatchworkArmor()) {
				model.put("patchworkByLoc", addPatchworkATs(tank, SH_TANK_ARMOR_LOCS));
			}
		} else {
			model.put("structureValues", addArmorStructureEntries(tank,
					(en, loc) -> en.getOInternal(loc),
					TANK_ARMOR_LOCS));
			model.put("armorValues", addArmorStructureEntries(tank,
					(en, loc) -> en.getOArmor(loc),
					TANK_ARMOR_LOCS));
			if (tank.hasPatchworkArmor()) {
				model.put("patchworkByLoc", addPatchworkATs(tank, TANK_ARMOR_LOCS));
			}
		}
	}
	
	private void addArmorAndStructure(Aero aero) {
		model.put("armorValues", addArmorStructureEntries(aero,
				(en, loc) -> en.getOArmor(loc),
				AERO_ARMOR_LOCS));
		if (aero.hasPatchworkArmor()) {
			model.put("patchworkByLoc", addPatchworkATs(aero, AERO_ARMOR_LOCS));
		}
	}
	
	/**
	 * Convenience method to format armor and structure values, consolidating right/left values into a single
	 * entry. In most cases the right and left armor values are the same, in which case only a single value
	 * is used. If the values do not match they are both (or all, in the case of tripod mech legs) given
	 * separated by slashes.
	 * 
	 * @param entity    The entity to collect structure or armor values for
	 * @param provider  The function that retrieves the armor or structure value for the entity and location
	 * @param locSets   An two-dimensional array that groups locations that should appear on the same line.
	 * 					Any location that is not legal for the unit (e.g. center leg on non-tripods) is
	 *                	ignored. If the first location in a group is illegal, the entire group is skipped.
	 * @return			A {@link Map} with the armor/structure value mapped to the abbreviation of each
	 * 					of the location keys.
	 */
	private Map<String, String> addArmorStructureEntries(Entity entity,
			BiFunction<Entity, Integer, Integer> provider, int[][] locSets) {
		Map<String, String> retVal = new HashMap<>();
		for (int[] locs : locSets) {
			if ((locs.length == 0) || (locs[0] >= entity.locations())) {
				continue;
			}
			String val = null;
			if (locs.length > 1) {
				for (int i = 1; i < locs.length; i++) {
					if ((locs[i] < entity.locations())
							&& ((provider.apply(entity,  locs[i]) != provider.apply(entity, locs[0]))
									|| entity.hasETypeFlag(Entity.ETYPE_AERO))) {
						val = Arrays.stream(locs)
								.mapToObj(l -> String.valueOf(provider.apply(entity, l)))
								.collect(Collectors.joining("/"));
						break;
					}
				}
			}
			if (null == val) {
				val = String.valueOf(provider.apply(entity, locs[0]));
			}
			for (int loc : locs) {
				if (loc < entity.locations()) {
					retVal.put(entity.getLocationAbbr(loc), val);
				}
			}
		}
		return retVal;
	}
	
	private Map<String, String> addPatchworkATs(Entity entity, int[][] locSets) {
		Map<String, String> retVal = new HashMap<>();
		for (int[] locs : locSets) {
			if ((locs.length == 0) || (locs[0] >= entity.locations())) {
				continue;
			}
			String val = null;
			if (locs.length > 1) {
				for (int i = 1; i < locs.length; i++) {
					if ((locs[i] < entity.locations())
							&& entity.getArmorType(locs[i]) != entity.getArmorType(locs[0])) {
						val = Arrays.stream(locs)
								.mapToObj(l -> formatArmorType(entity.getArmorType(l), true))
								.collect(Collectors.joining("/"));
						break;
					}
				}
			}
			if (null == val) {
				val = formatArmorType(entity.getArmorType(locs[0]), true);
			}
			for (int loc : locs) {
				if (loc < entity.locations()) {
					retVal.put(entity.getLocationAbbr(loc), val);
				}
			}
		}
		return retVal;
	}
	
	private int addEquipment(Entity entity) {
		final int structure = entity.getStructureType();
		final Map<String, Map<EquipmentType, Integer>> equipment = new HashMap<>();
		int nameWidth = 30;
		for (Mounted m : entity.getEquipment()) {
			if ((m.getLocation() < 0) || m.isWeaponGroup()) {
				continue;
			}
			if (!m.getType().isHittable()) {
				if ((structure != EquipmentType.T_STRUCTURE_UNKNOWN)
						&& (EquipmentType.getStructureType(m.getType()) == structure)) {
					continue;
				}
				if (entity.getArmorType(m.getLocation()) == EquipmentType.getArmorType(m.getType())) {
					continue;
				}
			}
			if (m.isOmniPodMounted() || !entity.isOmni()) {
				String loc = formatLocationTableEntry(entity, m);
				equipment.putIfAbsent(loc, new HashMap<>());
				equipment.get(loc).merge(m.getType(), 1, Integer::sum);
			}
		}
		final List<Map<String, Object>> eqList = new ArrayList<>();
		for (String loc : equipment.keySet()) {
			for (Map.Entry<EquipmentType, Integer> entry : equipment.get(loc).entrySet()) {
				final EquipmentType eq = entry.getKey();
				final int count = equipment.get(loc).get(eq);
				String name = stripNotes(eq.getName());
				if (eq instanceof AmmoType) {
					name = String.format("%s (%d)", name,
							((AmmoType) eq).getShots() * count);
				} else if (count > 1) {
					name = String.format("%d %ss", count, eq.getName());
				}
				Map<String, Object> fields = new HashMap<>();
				fields.put("name", name);
				if (name.length() >= nameWidth) {
					nameWidth = name.length() + 1;
				}
				fields.put("tonnage", eq.getTonnage(entity) * count);
				if (eq instanceof WeaponType) {
					fields.put("heat", ((WeaponType) eq).getHeat() * count);
				} else {
					fields.put("heat", "-");
				}
				if (eq.isSpreadable()) {
					Map<Integer, Integer> byLoc = getSpreadableLocations(entity, eq);
					final StringJoiner locs = new StringJoiner("/");
					final StringJoiner crits = new StringJoiner("/");
					byLoc.forEach((l, c) -> {
						locs.add(entity.getLocationAbbr(l));
						crits.add(String.valueOf(c));
					});
					fields.put("location", locs.toString());
					fields.put("slots", crits.toString());
				} else {
					fields.put("location", loc);
					fields.put("slots", eq.getCriticals(entity) * count);
				}
				eqList.add(fields);
			}
		}
		model.put("equipment", eqList);
		return nameWidth;
	}
	
	private int addBAEquipment(BattleArmor ba) {
		final List<Map<String, Object>> equipment = new ArrayList<>();
		final List<Map<String, Object>> modularEquipment = new ArrayList<>();
		String at = EquipmentType.getBaArmorTypeName(ba.getArmorType(BattleArmor.LOC_TROOPER_1),
				TechConstants.isClan(ba.getArmorTechLevel(BattleArmor.LOC_TROOPER_1)));
		final EquipmentType armor = EquipmentType.get(at);
		Map<String, Object> row = null;
		int nameWidth = 30;
		for (Mounted m : ba.getEquipment()) {
			if (m.isAPMMounted() || (m.getType() instanceof InfantryAttack)
					|| (m.getType() == armor)) {
				continue;
			}
			if ((m.getType() instanceof MiscType)
					&& m.getType().hasFlag(MiscType.F_BA_MANIPULATOR)) {
				continue;
			}
			row = new HashMap<>();
			String name = stripNotes(m.getName());
			if (m.getType() instanceof AmmoType) {
				row.put("name", name.replaceAll("^BA\\s+", "") + " (" + m.getOriginalShots() + ")");
			} else {
				row.put("name", stripNotes(m.getName()));
			}
			row.put("location", BattleArmor.getBaMountLocAbbr(m.getBaMountLoc()));
			if (name.length() >= nameWidth) {
				nameWidth = name.length() + 1;
			}
			row.put("slots", m.getType().getCriticals(ba));
			if (m.getType() instanceof AmmoType) {
				row.put("mass", ((AmmoType) m.getType()).getKgPerShot() * m.getOriginalShots());
			} else {
				row.put("mass", m.getType().getTonnage(ba) * 1000);
			}
			if (m.getBaMountLoc() == BattleArmor.MOUNT_LOC_TURRET) {
				row.put("location", "-");
				modularEquipment.add(row);
			} else {
				equipment.add(row);
			}
		}
		model.put("equipment", equipment);
		model.put("modularEquipment", modularEquipment);
		return nameWidth;
	}
	
	private Map<Integer, Integer> getSpreadableLocations(final Entity entity, final EquipmentType eq) {
		Map<Integer, Integer> retVal = new HashMap<>();
		for (int loc = 0; loc < entity.locations(); loc++) {
			for (int slot = 0; slot < entity.getNumberOfCriticals(loc); slot++) {
				final CriticalSlot crit = entity.getCritical(loc, slot);
				if ((crit != null) && (crit.getMount() != null) && (crit.getMount().getType() == eq)) {
					retVal.merge(loc, 1, Integer::sum);
				}
			}
		}
		return retVal;
	}
	
	private void addFixedOmni(final Entity entity) {
		double fixedTonnage = 0.0;
		final List<Map<String, Object>> fixedList = new ArrayList<>();
		for (int loc = 0; loc < entity.locations(); loc++) {
			if (entity.isAero() && (loc == Aero.LOC_WINGS)) {
				break;
			}
			int remaining = 0;
			Map<String, Integer> fixedCount = new HashMap<>();
			Map<String, Double> fixedWeight = new HashMap<>();
			for (int slot = 0; slot < entity.getNumberOfCriticals(loc); slot++) {
				CriticalSlot crit = entity.getCritical(loc, slot);
				if (null == crit) {
					remaining++;
				} else if ((crit.getType() == CriticalSlot.TYPE_SYSTEM)
						&& showFixedSystem(entity, crit.getIndex(), loc)) {
					fixedCount.merge(getSystemName(entity, crit.getIndex()), 1, Integer::sum);
				} else if (crit.getMount() != null) {
					if (crit.getMount().isOmniPodMounted()) {
						remaining++;
					} else if (!crit.getMount().isWeaponGroup()) {
						String key = stripNotes(crit.getMount().getType().getName());
						fixedCount.merge(key, 1, Integer::sum);
						fixedWeight.merge(key, crit.getMount().getType().getTonnage(entity), Double::sum);
					}
				}
			}
			Map<String, Object> row;
			if (fixedCount.isEmpty()) {
				row = new HashMap<>();
				row.put("location", entity.getLocationName(loc));
				row.put("equipment", "None");
				row.put("remaining", remaining);
				row.put("tonnage", 0.0);
				fixedList.add(row);
			} else {
				boolean firstLine = true;
				for (Map.Entry<String, Integer> entry : fixedCount.entrySet()) {
					row = new HashMap<>();
					if (firstLine) {
						row.put("location", entity.getLocationName(loc));
						row.put("remaining", remaining);
						firstLine = false;
					} else {
						row.put("location", "");
						row.put("remaining", "");
					}
					if (entry.getValue() > 1) {
						row.put("equipment", entry.getValue() + " " + entry.getKey());
					} else {
						row.put("equipment", entry.getKey());
					}
					row.put("tonnage", fixedWeight.get(entry.getKey()));
					fixedTonnage += fixedWeight.get(entry.getKey());
					fixedList.add(row);
				}
			}
		}
		model.put("fixedEquipment", fixedList);
		model.put("fixedTonnage", fixedTonnage);
	}
	
	private boolean showFixedSystem(Entity entity, int index, int loc) {
		if (entity.hasETypeFlag(Entity.ETYPE_MECH)) {
			return ((index != Mech.SYSTEM_COCKPIT) || (loc != Mech.LOC_HEAD))
					&& ((index != Mech.SYSTEM_SENSORS) || (loc != Mech.LOC_HEAD))
					&& ((index != Mech.SYSTEM_LIFE_SUPPORT) || (loc != Mech.LOC_HEAD))
					&& ((index != Mech.SYSTEM_ENGINE) || (loc != Mech.LOC_CT))
					&& (index != Mech.SYSTEM_GYRO)
					&& (index != Mech.ACTUATOR_SHOULDER)
					&& (index != Mech.ACTUATOR_UPPER_ARM)
					&& (index != Mech.ACTUATOR_LOWER_ARM)
					&& (index != Mech.ACTUATOR_HAND)
					&& (index != Mech.ACTUATOR_HIP)
					&& (index != Mech.ACTUATOR_UPPER_LEG)
					&& (index != Mech.ACTUATOR_LOWER_LEG)
					&& (index != Mech.ACTUATOR_FOOT);
		}
		return false;
	}
	
	private String getSystemName(Entity entity, int index) {
		if (entity.hasETypeFlag(Entity.ETYPE_MECH)) {
			// Here we're only concerned with engines that take extra critical slots in the side torso
			if (index == Mech.SYSTEM_ENGINE) {
				StringBuilder sb = new StringBuilder();
				if (entity.getEngine().hasFlag(Engine.LARGE_ENGINE)) {
					sb.append("Large ");
				}
				switch (entity.getEngine().getEngineType()) {
				case Engine.XL_ENGINE:
					sb.append("XL");
					break;
				case Engine.LIGHT_ENGINE:
					sb.append("Light");
					break;
				case Engine.XXL_ENGINE:
					sb.append("XXL");
					break;
				}
				sb.append(" Engine");
				return sb.toString();
			} else {
				return ((Mech) entity).getRawSystemName(index);
			}
		}
		return "Unknown System";
	}
	
	/**
	 * Formats displayable location name for use in equipment table. The format of the name can
	 * vary by unit type due to available space based on number of columns, and in some cases the
	 * official TROs have different location names than the ones used by MM.
	 * 
	 * @param entity   The entity the TRO is created for
	 * @param mounted  The mounted equipment
	 * @param rear     Whether the equipment is rear mounted
	 * @return         The location name to use in the table.
	 */
	private String formatLocationTableEntry(Entity entity, Mounted mounted) {
		if (entity.hasETypeFlag(Entity.ETYPE_MECH)) {
			String loc = entity.getLocationAbbr(mounted.getLocation());
			if (mounted.isRearMounted()) {
				loc += "(R)";
			}
			return loc;
		}
		if (entity.hasETypeFlag(Entity.ETYPE_TANK)) {
			return entity.getLocationName(mounted.getLocation());
		}
		// Default: location abbreviation
		return entity.getLocationAbbr(mounted.getLocation());
	}
	
	/**
	 * Formats {@link Transporter} to display as a row in an equipment table. Any other than bays
	 * and troop space are skipped to avoid showing BA handles and such.
	 *  
	 * @param transporter The transporter to show.
	 * @param loc         The location name to display on the table.
	 * @return            A map of values used by the equipment tables (omni fixed and pod/non-omni).
	 * 					  Returns {@code null} for a type of {@link Transporter} that should not be shown.
	 */
	private @Nullable Map<String, Object> formatTransporter(Transporter transporter, String loc) {
		Map<String, Object> retVal = new HashMap<>();
		if (transporter instanceof TroopSpace) {
			retVal.put("name", Messages.getString("TROView.TroopSpace"));
			retVal.put("tonnage", transporter.getUnused());
		} else if (transporter instanceof Bay) {
			BayData bayType = BayData.getBayType((Bay) transporter);
			retVal.put("name", bayType.getDisplayName());
			retVal.put("tonnage", bayType.getWeight() * transporter.getUnused());
		} else {
			return null;
		}
		retVal.put("equipment", retVal.get("name"));
		retVal.put("location", loc);
		retVal.put("slots", 1);
		retVal.put("heat", "-");
		return retVal;
	}
	
	private String formatJJDesc(Mech mech) {
		switch (mech.getJumpType()) {
			case Mech.JUMP_STANDARD:
				return Messages.getString("TROView.jjStandard");
			case Mech.JUMP_IMPROVED:
				return Messages.getString("TROView.jjImproved");
			case Mech.JUMP_PROTOTYPE:
				return Messages.getString("TROView.jjPrototype");
			case Mech.JUMP_PROTOTYPE_IMPROVED:
				return Messages.getString("TROView.jjImpPrototype");
			case Mech.JUMP_BOOSTER:
				return Messages.getString("TROView.jjBooster");
			default:
				return Messages.getString("TROView.jjNone");
		}
	}
	
	enum Justification {
		LEFT ((str, w) -> String.format("%-" + w + "s", str)),
		CENTER ((str, w) -> {
			if (w > str.length()) {
				int rightPadding = Math.max(0, (w - str.length()) / 2);
				if (rightPadding > 0) {
					str = String.format("%-" + (w - rightPadding) + "s", str);
				}
				return String.format("%" + w + "s", str);
			}
			return str;
		}),
		RIGHT ((str, w) -> String.format("%" + w + "s", str));
		
		final private BiFunction<String, Integer, String> pad;
		private Justification(BiFunction<String, Integer, String> pad) {
			this.pad = pad;
		}
		
		public String padString(String str, int fieldWidth) {
			if (fieldWidth > 0) {
				return pad.apply(str, fieldWidth);
			} else {
				return str;
			}
		}
	};

	/**
	 * Removes parenthetical and bracketed notes from a String
	 * @param str The String to process
	 * @return    The same String with notes removed
	 */
	private String stripNotes(String str) {
		return str.replaceAll("\\s+\\[.*?\\]", "")
				.replaceAll("\\s+\\(.*?\\)", "");
	}

	static class FormatTableRowMethod implements TemplateMethodModelEx {
		final private int[] colWidths;
		final private Justification[] justification;
		
		public FormatTableRowMethod(int[] widths, Justification[] justify) {
			colWidths = new int[widths.length];
			justification = new Justification[widths.length];
			for (int i = 0; i < widths.length; i++) {
				colWidths[i] = widths[i];
				if (i < justify.length) {
					justification[i] = justify[i];
				} else {
					justification[i] = Justification.LEFT;
				}
			}
			System.arraycopy(widths, 0, colWidths, 0, widths.length);
		}
		
		@Override
		public Object exec(@SuppressWarnings("rawtypes") List arguments) throws TemplateModelException {
			StringBuilder sb = new StringBuilder();
			int col = 0;
			for (Object o : arguments) {
				if (col < colWidths.length) {
					sb.append(justification[col].padString(o.toString(), colWidths[col]));
				} else {
					sb.append(o);
				}
				col++;
			}
			return sb.toString();
		}
		
	}
	
	/**
	 * Sets whether to include the fluff section when processing the template
	 * 
	 * @param includeFluff Whether to include the fluff section
	 */
	public void setIncludeFluff(boolean includeFluff) {
		this.includeFluff = includeFluff;
	}
	
	/**
	 * 
	 * @return Whether the fluff section will be included when processing the template
	 */
	public boolean getIncludeFluff() {
		return includeFluff;
	}
}
