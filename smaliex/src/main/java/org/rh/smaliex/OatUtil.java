/*
 * Copyright (C) 2014 Riddle Hsu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rh.smaliex;

import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.smaliex.DexUtil.ODexRewriter;
import org.rh.smaliex.reader.DataReader;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class OatUtil {
    public static boolean SKIP_EXISTS;

    public static Opcodes getOpcodes(Oat oat) {
        return DexUtil.getOpcodes(VersionMap.mapArtVersionToApi(oat.getArtVersion()));
    }

    @Nonnull
    public static List<DexBackedDexFile> getDexFiles(@Nonnull File file,
                                                     int apiLevel,
                                                     @Nullable List<String> outputNames) {
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        if (MiscUtil.isElf(file)) {
            try (Elf e = new Elf(file)) {
                Oat oat = getOat(e);
                Opcodes opc = apiLevel > 0 ? DexUtil.getOpcodes(apiLevel) : getOpcodes(oat);
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.DexFile df = oat.mDexFiles[i];
                    dexFiles.add(new DexBackedDexFile(opc, df.getBytes()));
                    if (outputNames != null) {
                        String dexName = new String(oat.mOatDexFiles[i].dex_file_location_data_);
                        dexName = getOutputNameForSubDex(dexName);
                        outputNames.add(MiscUtil.getFilenameNoExt(dexName));
                    }
                }
            } catch (IOException ex) {
                LLog.ex(ex);
            }
        } else {
            Opcodes opc = DexUtil.getOpcodes(apiLevel > 0 ? apiLevel : DexUtil.DEFAULT_API_LEVEL);
            dexFiles = DexUtil.loadMultiDex(file, opc);
            if (outputNames != null) {
                String dexName = "classes";
                for (int i = 0; i < dexFiles.size(); i++) {
                    outputNames.add(dexName);
                    dexName = "classes" + (i + 2);
                }
            }
        }
        return dexFiles;
    }

    /**
     * Extract smali from odex or oat file.
     *
     * If the input file has multiple embedded dex files, the smali files will be placed into subdirectories
     * within the output directory. Otherwise, the smali files are placed directly into the output directory.
     *
     * If there are multiple dex files in the input file, the subdirectories will be named as follows:
     * 1. If the input file is an oat file, then the subdirectory name is based on the dex_file_location_data_ field
     * 2. If the input file is not an oat file, then the subdirectory name is "classes", "classes2", etc.
     *
     * @param inputFile Input odex or oat file
     * @param outputPath Path to output directory. If null, the output will put at the same place of input
     */
    public static void smaliRaw(@Nonnull File inputFile,
                                @Nullable String outputPath, int apiLevel) {
        if (!inputFile.isFile()) {
            LLog.i(inputFile + " is not a file. ");
        }
        String folderName = MiscUtil.getFilenameNoExt(inputFile.getName());
        String outputBaseDir = outputPath != null ? outputPath
                : MiscUtil.path(inputFile.getAbsoluteFile().getParent(), folderName);
        BaksmaliOptions options = new BaksmaliOptions();
        Opcodes opc = DexUtil.getOpcodes(apiLevel);
        options.apiLevel = opc.api;
        options.allowOdex = true;

        List<String> outSubDirs = new ArrayList<>();
        List<DexBackedDexFile> dexFiles = getDexFiles(inputFile, apiLevel, outSubDirs);
        if (outSubDirs.size() == 1) {
            outSubDirs.set(0, outputBaseDir);
        } else {
            for (int i = 0; i < outSubDirs.size(); i++) {
                outSubDirs.set(i, MiscUtil.path(outputBaseDir, outSubDirs.get(i)));
            }
        }

        for (int i = 0; i < dexFiles.size(); i++) {
            File outputDir = new File(outSubDirs.get(i));
            org.jf.baksmali.Baksmali.disassembleDexFile(dexFiles.get(i), outputDir, 4, options);
            LLog.i("Output to " + outputDir);
        }
        LLog.i("All done");
    }

    /**
     * Output jar with de-optimized dex and also pack with other files from original jar
     *
     * @param bootOat Path to boot*.oat files
     * @param noClassJarPath Path to jars without dex
     * @param outputJarPath Path to output the repacked jar
     * @throws IOException Failed to read oat,dex file
     */
    public static void bootOat2Jar(@Nonnull String bootOat,
                                   @Nullable String noClassJarPath,
                                   @Nullable String outputJarPath) throws IOException {
        convertDexFromBootOat(bootOat, outputJarPath, bootOat, noClassJarPath, true);
    }

    /**
     * Convert boot*.oat to dex.
     *
     * @param bootOat Path to boot*.oat file(s)
     * @param outPath Output directory for odex files. If null, it default puts at the same place of input.
     * @throws IOException Failed to read oat or write odex, dex
     */
    public static void bootOat2Dex(@Nonnull String bootOat,
                                   @Nullable String outPath) throws IOException {
        convertDexFromBootOat(bootOat, outPath, bootOat, null, true);
    }

    public static void oat2dex(@Nonnull String oatPath,
                               @Nonnull String bootClassPath,
                               @Nullable String outPath) throws IOException {
        convertDexFromBootOat(oatPath, outPath, bootClassPath, null, false);
    }

    @Nonnull
    public static Oat getOat(@Nonnull Elf e) throws IOException {
        DataReader r = e.getReader();
        // Currently the same as e.getSymbolTable("oatdata").getOffset(e)
        Elf.Elf_Shdr sec = e.getSection(Oat.SECTION_RODATA);
        if (sec != null) {
            r.seek(sec.getOffset());
            return new Oat(r);
        }
        throw new IOException("oat not found");
    }

    @Nonnull
    public static ArrayList<String> getBootJarNames(@Nonnull String bootPath, boolean fullPath) {
        ArrayList<String> names = new ArrayList<>();
        for (File oatFile : getOatFile(new File(bootPath))) {
            try (Elf e = new Elf(oatFile)) {
                Oat oat = getOat(e);
                for (Oat.OatDexFile df : oat.mOatDexFiles) {
                    String s = new String(df.dex_file_location_data_);
                    if (s.contains(":")) {
                        continue;
                    }
                    names.add(fullPath ? s : s.substring(s.lastIndexOf('/') + 1));
                }
            } catch (IOException ex) {
                LLog.ex(ex);
            }
        }
        return names;
    }

    @Nonnull
    public static String getOutputNameForSubDex(@Nonnull String jarPathInOat) {
        int colonPos = jarPathInOat.indexOf(':');
        if (colonPos > 0) {
            // framework.jar:classes2.dex -> framework-classes2.dex
            jarPathInOat = jarPathInOat.substring(0, colonPos - 4)
                    + "-" + jarPathInOat.substring(colonPos + 1);
        }
        return jarPathInOat.substring(jarPathInOat.lastIndexOf('/') + 1);
    }

    @Nonnull
    static File ensureOutputDir(@Nonnull File src, @Nullable File out, @Nonnull String postfix) {
        if (out == null) {
            out = new File(src.getParentFile(), src.getName() + postfix);
        }
        return out;
    }

    @Nonnull
    static File ensureOutputDir(@Nonnull String src, @Nullable String out, @Nonnull String postfix) {
        return ensureOutputDir(new File(src), out == null ? null : new File(out), postfix);
    }

    @Nonnull
    static File[] getOatFile(@Nonnull File oatPath) {
        return oatPath.isDirectory()
                ? MiscUtil.getFiles(oatPath.getAbsolutePath(), ".oat;.odex")
                : new File[] { oatPath };
    }

    /**
     * Extract odex files from oat file.
     *
     * NOTE: The odex files created in the output directory will have the ".dex" extension if
     * the input oat also used ".odex" extension.
     *
     * @param oatPath Path to boot*.oat file(s)
     * @param outputDir Output directory. If null, it will puts at the same place of input oat.
     * @return The directory which contains odex files
     * @throws IOException Failed to read oat or write odex
     */
    @Nonnull
    public static File extractOdexFromOat(@Nonnull File oatPath,
                                          @Nullable File outputDir) throws IOException {
        outputDir = ensureOutputDir(oatPath, outputDir, "-odex");
        MiscUtil.mkdirs(outputDir);
        IOException ioe = null;
        for (File oatFile : getOatFile(oatPath)) {
            if (!MiscUtil.isElf(oatFile)) {
                LLog.i("Skip not ELF: " + oatFile);
                continue;
            }
            try (Elf e = new Elf(oatFile)) {
                Oat oat = getOat(e);
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.OatDexFile odf = oat.mOatDexFiles[i];
                    Oat.DexFile df = oat.mDexFiles[i];
                    String outFile = new String(odf.dex_file_location_data_);
                    outFile = getOutputNameForSubDex(outFile);
                    File out = MiscUtil.changeExt(new File(outputDir, outFile),
                            oatFile.getName().endsWith(".odex") ? "dex" : "odex");
                    df.saveTo(out);
                    LLog.i("Output raw dex: " + out.getAbsolutePath());
                }
            } catch (IOException ex) {
                if (ioe == null) {
                    ioe = new IOException("Error at " + oatFile);
                }
                ioe.addSuppressed(ex);
            }
        }
        if (ioe != null) {
            throw handleIOE(ioe);
        }
        return outputDir;
    }

    public static void convertDexFromBootOat(@Nonnull String oatPath,
                                             @Nullable String outputPath,
                                             @Nonnull String bootClassPath,
                                             @Nullable String noClassJarFolder,
                                             boolean isBoot) throws IOException {
        boolean dexOnly = noClassJarFolder == null;
        File outDir = ensureOutputDir(oatPath, outputPath, dexOnly ? "-dex" : "-jar");
        MiscUtil.mkdirs(outDir);

        LLog.v("Use bootclasspath " + bootClassPath);
        IOException ioe = null;
        for (File oatFile : getOatFile(new File(oatPath))) {
            try (Elf e = new Elf(oatFile)) {
                Oat oat = getOat(e);
                if (dexOnly) {
                    convertToDex(oat, outDir, bootClassPath, isBoot);
                } else {
                    convertToDexJar(oat, outDir, bootClassPath, noClassJarFolder, isBoot);
                }
            } catch (IOException ex) {
                if (ioe == null) {
                    ioe = new IOException("Error at " + oatFile);
                }
                ioe.addSuppressed(ex);
            }
        }
        if (ioe != null) {
            throw handleIOE(ioe);
        }
    }

    /**
     * Get list of DexFile objects from an Oat file.
     *
     * @param oat Oat file
     * @param opcodes Opcodes for the API level (if null, guessed from oat file)
     * @return List of DexFile objects
     * @throws IOException Wrong file format
     */
    @Nonnull
    public static DexFile[] getOdexFromOat(@Nonnull Oat oat,
                                           @Nullable Opcodes opcodes) throws IOException {
        if (opcodes == null) {
            opcodes = getOpcodes(oat);
        }
        DexFile[] dexFiles = new DexFile[oat.mOatDexFiles.length];
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.DexFile dex = oat.mDexFiles[i];
            DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dex.getBytes());
            if (!DexUtil.verifyStringOffset(dexFile)) {
                LLog.i("Bad string offset.");
                throw new IOException("The dex does not have formal format in: " + oat.mSrcFile);
            }
            dexFiles[i] = dexFile;
        }
        return dexFiles;
    }

    /**
     * Convert oat file to dex files.
     *
     * @param oat Oat file
     * @param outputDir Output directory for dex files
     * @param bootClassPath Boot class path (path to framework odex files)
     * @param isBoot Whether the input oat to add dex files to boot class path field
     * @throws IOException Failed to read input or write output
     */
    private static void convertToDex(@Nonnull Oat oat, @Nonnull File outputDir,
                                     String bootClassPath, boolean isBoot) throws IOException {
        final Opcodes opcodes = getOpcodes(oat);
        if (bootClassPath == null || !new File(bootClassPath).exists()) {
            throw new IOException("Invalid bootclasspath: " + bootClassPath);
        }
        final ODexRewriter deOpt = DexUtil.getODexRewriter(bootClassPath, opcodes);
        if (LLog.VERBOSE) {
            deOpt.setFailInfoLocation(outputDir.getAbsolutePath());
        }

        LLog.i("Art version=" + oat.getArtVersion() + " (" + oat.mSrcFile + ")");
        DexFile[] dexFiles = getOdexFromOat(oat, opcodes);
        if (!isBoot) {
            for (DexFile d : dexFiles) {
                deOpt.addDexToClassPath(d);
            }
        }
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String dexLoc = new String(odf.dex_file_location_data_);
            String outputName = getOutputNameForSubDex(dexLoc);
            if ("base.apk".equals(outputName)) {
                outputName = MiscUtil.getFilenameNoExt(oat.mSrcFile.getName());
            }
            File outputFile = MiscUtil.changeExt(new File(outputDir, outputName), "dex");
            if (SKIP_EXISTS && outputFile.exists()) continue;

            LLog.i("De-optimizing " + dexLoc);
            DexFile d = deOpt.rewriteDexFile(dexFiles[i]);
            if (!ODexRewriter.isValid(d)) {
                LLog.i("convertToDex: skip " + dexLoc);
                continue;
            }

            if (outputFile.exists()) {
                MiscUtil.delete(outputFile);
                //File old = outputFile;
                //outputFile = MiscUtil.appendTail(outputFile, "-deodex");
                //LLog.i(old + " already existed, use name " + outputFile.getName());
            }
            DexPool.writeTo(outputFile.getAbsolutePath(), d);
            LLog.i("Output to " + outputFile);
        }
    }

    public static void convertToDexJar(@Nonnull Oat oat,
                                       @Nonnull File outputFolder,
                                       @Nonnull String bootClassPath,
                                       @Nonnull String noClassJarFolder,
                                       boolean isBoot) throws IOException {
        final Opcodes opcodes = getOpcodes(oat);
        final ODexRewriter deOpt = DexUtil.getODexRewriter(bootClassPath, opcodes);
        HashMap<String, ArrayList<Oat.DexFile>> dexFileGroup = new HashMap<>();
        for (int i = 0; i < oat.mOatDexFiles.length; i++) {
            Oat.OatDexFile odf = oat.mOatDexFiles[i];
            String dexPath = new String(odf.dex_file_location_data_);
            int colonPos = dexPath.indexOf(':');
            if (colonPos > 0) {
                // .../framework.jar:classes2.dex
                dexPath = dexPath.substring(0, colonPos);
            }
            dexPath = dexPath.substring(dexPath.lastIndexOf('/') + 1);
            ArrayList<Oat.DexFile> dexFiles = dexFileGroup.computeIfAbsent(dexPath, k -> new ArrayList<>());
            dexFiles.add(oat.mDexFiles[i]);
            if (!isBoot) {
                deOpt.addDexToClassPath(new DexBackedDexFile(opcodes, oat.mDexFiles[i].getBytes()));
            }
        }
        if (LLog.VERBOSE) {
            deOpt.setFailInfoLocation(outputFolder.getAbsolutePath());
        }

        for (String jarName : dexFileGroup.keySet()) {
            File outputJar = MiscUtil.changeExt(new File(outputFolder, jarName), "jar");
            if (SKIP_EXISTS && outputJar.exists()) continue;

            String classesIdx = "";
            int i = 1;
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar))) {
                for (Oat.DexFile dex : dexFileGroup.get(jarName)) {
                    jos.putNextEntry(new ZipEntry("classes" + classesIdx + ".dex"));
                    LLog.i("De-optimizing " + jarName + (i > 1 ? (" part-" + classesIdx) : ""));
                    DexFile d = new DexBackedDexFile(opcodes, dex.getBytes());
                    d = deOpt.rewriteDexFile(d);
                    if (!ODexRewriter.isValid(d)) {
                        LLog.i("convertToDexJar: skip " + jarName);
                        continue;
                    }

                    MemoryDataStore m = new MemoryDataStore(dex.mHeader.file_size_ + 512);
                    DexPool.writeTo(m, d);
                    m.writeTo(jos);
                    classesIdx = String.valueOf(++i);
                    jos.closeEntry();
                }

                // Copy files from original jar
                try (JarFile jarFile = new JarFile(new File(noClassJarFolder, jarName))) {
                    final Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        final JarEntry e = entries.nextElement();
                        String name = e.getName();
                        if (name.startsWith("classes") && name.endsWith(".dex")) {
                            continue;
                        }
                        jos.putNextEntry(new ZipEntry(name));
                        try (InputStream is = jarFile.getInputStream(e)) {
                            jos.write(MiscUtil.readBytes(is));
                        }
                        jos.closeEntry();
                    }
                }
                LLog.i("Output " + outputJar);
            } catch (IOException ex) {
                throw handleIOE(ex);
            }
        }
        deOpt.recycle();
    }

    static IOException handleIOE(IOException ex) {
        LLog.ex(ex);
        return ex;
    }
}
