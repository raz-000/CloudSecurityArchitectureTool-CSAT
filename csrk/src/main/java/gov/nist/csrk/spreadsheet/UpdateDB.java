package gov.nist.csrk.spreadsheet;

import gov.nist.csrk.jooq.tables.daos.*;
import gov.nist.csrk.jooq.tables.pojos.*;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Created by naw2 on 12/22/2017.
 */
public class UpdateDB {
    private final DSLContext context;
    private final BaselinesecuritymappingsDao baselinesecuritymappingsDao;
    private final ControlsDao controlsDao;
    private final SpecsDao specsDao;
    private final RelatedsDao relatedsDao;
    private final CapabilitiesDao capabilitiesDao;
    private final TicmappingsDao ticmappingsDao;
    private final MaptypescapabilitiescontrolsDao maptypescapabilitiescontrolsDao;

    private final gov.nist.csrk.jooq.tables.Specs SPECS;
    private final gov.nist.csrk.jooq.tables.Controls CONTROLS;

    private boolean implementation3Col = false;

    public UpdateDB(DSLContext context) {
        this.context = context;

        baselinesecuritymappingsDao = new BaselinesecuritymappingsDao(context.configuration());
        controlsDao = new ControlsDao(context.configuration());
        specsDao = new SpecsDao(context.configuration());
        relatedsDao = new RelatedsDao(context.configuration());
        capabilitiesDao = new CapabilitiesDao(context.configuration());
        ticmappingsDao = new TicmappingsDao(context.configuration());
        maptypescapabilitiescontrolsDao = new MaptypescapabilitiescontrolsDao(context.configuration());

        SPECS = gov.nist.csrk.jooq.tables.Specs.SPECS;
        CONTROLS = gov.nist.csrk.jooq.tables.Controls.CONTROLS;
    }

    public void setImplementation3Col(boolean implementation3Col) {
        this.implementation3Col = implementation3Col;
    }

    /**
     * Opens Excel sheet for reading
     * @param path Path to excel sheet
     * @param position Workbook to return
     */
    private static XSSFSheet openSheet(String path, int position) {
        XSSFWorkbook workbook;
        try {
            InputStream inputStream = new FileInputStream(path);
            OPCPackage pkg = OPCPackage.open(inputStream);
            workbook = new XSSFWorkbook(pkg);
            pkg.close();
        } catch (InvalidFormatException | IOException e) {
            e.printStackTrace();
            return null;
        }
        return workbook.getSheetAt(position);
    }

    /**
     * Update Capabilities, TIC Mappings and MapTypeCapabilitiesControls
     * @param path to workbook
     */
    public void updateCapabilities(String path) {
        XSSFSheet sheet = openSheet(path, 1);

        if(sheet == null)
            return;

        // process tic capabilities and tic mappings
        List<Capabilities> capabilities = new ArrayList<>();
        HashMap<String, Ticmappings> ticmappings = new HashMap<>();
        for(int i = 3; i < sheet.getPhysicalNumberOfRows(); i++) {
            XSSFRow row = sheet.getRow(i);
            Capabilities capability = new Capabilities();
            capability.setDomain(row.getCell(0).getStringCellValue());
            capability.setContainer(row.getCell(1).getStringCellValue());
            capability.setCapability(row.getCell(2).getStringCellValue());
            capability.setCapability2(row.getCell(3).getStringCellValue());
            capability.setDescription(row.getCell(4).getStringCellValue());
            // NOTE CSA Description missing
            String uniqueId = row.getCell(5).getStringCellValue();
            capability.setUniqueid(uniqueId);
            capability.setScopes(row.getCell(6).getStringCellValue());

            capability.setC((int) row.getCell(23).getNumericCellValue());
            capability.setI((int) row.getCell(24).getNumericCellValue());
            capability.setA((int) row.getCell(25).getNumericCellValue());

            capability.setResponsibilityvector(row.getCell(27).getStringCellValue() + "," +
                    row.getCell(31).getStringCellValue() + "," +
                    row.getCell(28).getStringCellValue() + "," +
                    row.getCell(32).getStringCellValue() + "," +
                    row.getCell(29).getStringCellValue() + "," +
                    row.getCell(33).getStringCellValue());
            capability.setOtheractors(row.getCell(39).getStringCellValue() + "," +
                    row.getCell(40).getStringCellValue() + "," +
                    row.getCell(41).getStringCellValue() + "," +
                    row.getCell(43).getStringCellValue() + "," +
                    row.getCell(45).getStringCellValue());

            String ticMappingsString = row.getCell(7).getStringCellValue();
            String[] entries = ticMappingsString.split("[;\n,]");
            for(String ticCap:entries) {
                Ticmappings ticData = new Ticmappings();
                ticData.setTicname(ticCap);
                ticmappings.put(uniqueId, ticData);
            }

            capabilities.add(capability);
        }
        capabilitiesDao.insert(capabilities);

        // find correct capabilitiesId for each ticMapping
        List<Ticmappings> ticList = new ArrayList<>();
        for(String uid:ticmappings.keySet()) {
            ticmappings.get(uid).setCapabilityid(capabilitiesDao.fetchByUniqueid(uid).get(0).getId());
            ticList.add(ticmappings.get(uid));
        }
        ticmappingsDao.insert(ticList);

        // process MapTypes
        List<Maptypescapabilitiescontrols> mapList = new ArrayList<>();
        for(int i = 3; i < sheet.getPhysicalNumberOfRows(); i++) {
            XSSFRow row = sheet.getRow(i);

            int capId = capabilitiesDao.fetchByUniqueid(row.getCell(5).getStringCellValue()).get(0).getId();

            for(int level = 1; level <= 7; level++) {
                String implementList;
                if(implementation3Col) {
                    implementList = row.getCell( 26 + level - 1).getStringCellValue();
                } else {
                    if(level <= 3) {
                        implementList = row.getCell(30 * (level - 1)).getStringCellValue() + ","
                                + row.getCell(30 * (level - 1) + 1).getStringCellValue(); // TODO this doesn't look right (UpdateCapabilities.cs:186)
                    } else {
                        implementList = row.getCell(26 + level - 4).getStringCellValue();
                    }
                }

                List<String> controls = getControlList(implementList);
                for(String controlName:controls) {
                    String topControl = removeSpec(controlName);

                    List<Controls> possibleControls = controlsDao.fetchByName(topControl);
                    int controlId = (possibleControls.isEmpty()) ? 0 : possibleControls.get(0).getId();

                    Result<Record1<Integer>> result = context.select(SPECS.ID)
                            .from(SPECS)
                            .where(SPECS.CONTROLSID.eq(controlId)
                                    .and(SPECS.SPECIFICATIONNAME.eq(getTopControlName(controlName))))
                            .fetch();
                    int specId = (result.isEmpty()) ? 0 : result.get(0).value1();

                    if(controlId > 0 || specId > 0) {
                        boolean isControl;
                        if(specId == 0) {
                            isControl = true;
                            specId = 1;
                        } else {
                            isControl = false;
                        }
                        Maptypescapabilitiescontrols map = new Maptypescapabilitiescontrols(-1, capId, controlId,
                                level, specId, isControl);
                        mapList.add(map);
                    }
                }
                maptypescapabilitiescontrolsDao.insert(mapList);
            }

        }

    }

    /**
     * Updates Controls, Specs, Relateds
     * @param path Path to workbook
     */
    public void updateControls(String path) {
        XSSFSheet sheet = openSheet(path, 2);

        if(sheet == null)
            return;

        for(int i = 1; i < sheet.getPhysicalNumberOfRows(); i++) {
            XSSFRow row = sheet.getRow(i);
            // process row
        }

    }

    /**
     * Update BaselineSecurityMappings table
     *
     * Requires controls and specs to be up to date
     * @param path to workbook
     */
    public void updateBaselineSecurityMappings(String path) {
        XSSFSheet sheet = openSheet(path, 0);

        if(sheet == null)
            return;

        for(int i = 1; i < sheet.getPhysicalNumberOfRows(); i++) {
            XSSFRow row = sheet.getRow(i);
            if(row.getPhysicalNumberOfCells() > 0) {    // TODO check if insertions are successful
                int AUTHOR_NIST = 1;
                int AUTHOR_FEDRAMP = 2;
                int LEVEL_LOW = 1;
                int LEVEL_MED = 2;
                int LEVEL_HIGH = 3;
                int COL_NIST_LOW = 2;
                int COL_FED_LOW = 3;
                int COL_NIST_MED = 5;
                int COL_FED_MED = 6;
                int COL_NIST_HIGH = 7;
                int COL_FED_HIGH = 8;
                insertBaselineSecurityMapping(row.getCell(COL_NIST_LOW).getStringCellValue(), LEVEL_LOW, AUTHOR_NIST);
                insertBaselineSecurityMapping(row.getCell(COL_FED_LOW).getStringCellValue(), LEVEL_LOW, AUTHOR_FEDRAMP);
                insertBaselineSecurityMapping(row.getCell(COL_NIST_MED).getStringCellValue(), LEVEL_MED, AUTHOR_NIST);
                insertBaselineSecurityMapping(row.getCell(COL_FED_MED).getStringCellValue(), LEVEL_MED, AUTHOR_FEDRAMP);
                insertBaselineSecurityMapping(row.getCell(COL_NIST_HIGH).getStringCellValue(), LEVEL_HIGH, AUTHOR_NIST);
                insertBaselineSecurityMapping(row.getCell(COL_FED_HIGH).getStringCellValue(), LEVEL_HIGH, AUTHOR_FEDRAMP);
            }
        }

    }

    /**
     * Insert new record into BaselineSecurityMappings with string containing many controls
     * @param component String containing many controls (parsed out with regex)
     * @param level (low, medium, or high corresponding to 1, 2 or 3)
     * @param author (1 denotes NIST, 2 denotes FEDRAMP)
     */
    private void insertBaselineSecurityMapping(String component, int level, int author) {
        String[] controls = (String[]) getControlList(component).toArray();
        for(String entry:controls) {
            boolean isControlMap = Pattern.matches("[A-Z]{2}-([0-9]{1,2})", entry);
            int specsId = 1;
            int controlsId = 1;
            if(isControlMap) {
                List<Controls> filteredControls = controlsDao.fetchByName(entry);
                if(filteredControls.size() >= 1) {
                    controlsId = filteredControls.get(0).getId();
                } else {
                    return; // TODO throw an error or something
                }
            } else {
                String top = removeSpec(entry);
                // get controls id
                Result<Record1<Integer>> result = context.select(CONTROLS.ID).from(CONTROLS)
                        .where(CONTROLS.NAME.eq(top)).fetch();
                int specCotrolId = result.isEmpty() ? 0 : result.get(0).value1();
                if(specCotrolId == 0) {
                    return; // TODO throw an error or something
                }
                // get specs id
                result = context.select(SPECS.ID).from(SPECS)
                        .where(SPECS.CONTROLSID.eq(specCotrolId)
                                .and(SPECS.SPECIFICATIONNAME.eq(entry.replace(top, "")))).fetch();
                specsId = result.isEmpty() ? 0 : result.get(0).value1();
            }

            Baselinesecuritymappings baseline = new Baselinesecuritymappings(
                    -1, level, author, isControlMap, specsId, controlsId);
            baselinesecuritymappingsDao.insert(baseline);
        }
    }

    private static List<String> getControlList(String rawString) {
        //noinspection RegExpRedundantEscape
        String[] rawControls = rawString.split("([,;\\n\\t *\\[\\]\\{\\}])");

        List<String> controls = new ArrayList<>();
        for(String potentialControl:rawControls) {
            if(Pattern.matches("[A-Z]{2}-([0-9]{1,2})(\\((\\d|\\d\\d)\\)|)?", potentialControl)) {
                controls.add(potentialControl);
            } else {
                System.out.println("Malformed control: Pattern mismatch for " + potentialControl);
            }
        }
        return controls;
    }

    private static String getTopControlName(String rawString) {
        rawString = rawString.replace(" ", "");
        rawString = rawString.replaceAll("[A-Z]{2}-([0-9]{1,2})", "");

        return rawString;
    }

    private static String removeSpec(String control) {
        String tail = getTopControlName(control);
        return (control.equals("")) ? control : control.substring(0, control.indexOf(tail));
    }
}