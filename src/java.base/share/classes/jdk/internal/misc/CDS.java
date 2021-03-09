/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.misc;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import java.io.InputStreamReader;
import java.io.BufferedReader;

import jdk.internal.access.JavaLangInvokeAccess;
import jdk.internal.access.SharedSecrets;

public class CDS {
    private static final boolean isDumpingClassList;
    private static final boolean isDumpingArchive;
    private static final boolean isSharingEnabled;
    static {
        isDumpingClassList = isDumpingClassList0();
        isDumpingArchive = isDumpingArchive0();
        isSharingEnabled = isSharingEnabled0();
    }

    /**
      * indicator for dumping class list.
      */
    public static boolean isDumpingClassList() {
        return isDumpingClassList;
    }

    /**
      * Is the VM writing to a (static or dynamic) CDS archive.
      */
    public static boolean isDumpingArchive() {
        return isDumpingArchive;
    }

    /**
      * Is sharing enabled via the UseSharedSpaces flag.
      */
    public static boolean isSharingEnabled() {
        return isSharingEnabled;
    }
    private static native String[] getVMArguments(); // return commandline args except for executable itself.
    private static native boolean isDumpingClassList0();
    private static native boolean isDumpingArchive0();
    private static native boolean isSharingEnabled0();
    private static native void logLambdaFormInvoker(String line);

    /**
     * Initialize archived static fields in the given Class using archived
     * values from CDS dump time. Also initialize the classes of objects in
     * the archived graph referenced by those fields.
     *
     * Those static fields remain as uninitialized if there is no mapped CDS
     * java heap data or there is any error during initialization of the
     * object class in the archived graph.
     */
    public static native void initializeFromArchive(Class<?> c);

    /**
     * Ensure that the native representation of all archived java.lang.Module objects
     * are properly restored.
     */
    public static native void defineArchivedModules(ClassLoader platformLoader, ClassLoader systemLoader);

    /**
     * Returns a predictable "random" seed derived from the VM's build ID and version,
     * to be used by java.util.ImmutableCollections to ensure that archived
     * ImmutableCollections are always sorted the same order for the same VM build.
     */
    public static native long getRandomSeedForDumping();

    /**
     * log lambda form invoker holder, name and method type
     */
    public static void traceLambdaFormInvoker(String prefix, String holder, String name, String type) {
        if (isDumpingClassList) {
            logLambdaFormInvoker(prefix + " " + holder + " " + name + " " + type);
        }
    }

    /**
      * log species
      */
    public static void traceSpeciesType(String prefix, String cn) {
        if (isDumpingClassList) {
            logLambdaFormInvoker(prefix + " " + cn);
        }
    }

    static final String DIRECT_HOLDER_CLASS_NAME  = "java.lang.invoke.DirectMethodHandle$Holder";
    static final String DELEGATING_HOLDER_CLASS_NAME = "java.lang.invoke.DelegatingMethodHandle$Holder";
    static final String BASIC_FORMS_HOLDER_CLASS_NAME = "java.lang.invoke.LambdaForm$Holder";
    static final String INVOKERS_HOLDER_CLASS_NAME = "java.lang.invoke.Invokers$Holder";

    private static boolean isValidHolderName(String name) {
        return name.equals(DIRECT_HOLDER_CLASS_NAME)      ||
               name.equals(DELEGATING_HOLDER_CLASS_NAME)  ||
               name.equals(BASIC_FORMS_HOLDER_CLASS_NAME) ||
               name.equals(INVOKERS_HOLDER_CLASS_NAME);
    }

    private static boolean isBasicTypeChar(char c) {
         return "LIJFDV".indexOf(c) >= 0;
    }

    private static boolean isValidMethodType(String type) {
        String[] typeParts = type.split("_");
        // check return type (second part)
        if (typeParts.length != 2 || typeParts[1].length() != 1
                || !isBasicTypeChar(typeParts[1].charAt(0))) {
            return false;
        }
        // first part
        if (!isBasicTypeChar(typeParts[0].charAt(0))) {
            return false;
        }
        for (int i = 1; i < typeParts[0].length(); i++) {
            char c = typeParts[0].charAt(i);
            if (!isBasicTypeChar(c)) {
                if (!(c >= '0' && c <= '9')) {
                    return false;
                }
            }
        }
        return true;
    }

    // Throw exception on invalid input
    private static void validateInputLines(String[] lines) {
        for (String s: lines) {
            if (!s.startsWith("[LF_RESOLVE]") && !s.startsWith("[SPECIES_RESOLVE]")) {
                throw new IllegalArgumentException("Wrong prefix: " + s);
            }

            String[] parts = s.split(" ");
            boolean isLF = s.startsWith("[LF_RESOLVE]");

            if (isLF) {
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Incorrect number of items in the line: " + parts.length);
                }
                if (!isValidHolderName(parts[1])) {
                    throw new IllegalArgumentException("Invalid holder class name: " + parts[1]);
                }
                if (!isValidMethodType(parts[3])) {
                    throw new IllegalArgumentException("Invalid method type: " + parts[3]);
                }
            } else {
                if (parts.length != 2) {
                   throw new IllegalArgumentException("Incorrect number of items in the line: " + parts.length);
                }
           }
      }
    }

    /**
     * called from vm to generate MethodHandle holder classes
     * @return {@code Object[]} if holder classes can be generated.
     * @param lines in format of LF_RESOLVE or SPECIES_RESOLVE output
     */
    private static Object[] generateLambdaFormHolderClasses(String[] lines) {
        Objects.requireNonNull(lines);
        validateInputLines(lines);
        Stream<String> lineStream = Arrays.stream(lines);
        Map<String, byte[]> result = SharedSecrets.getJavaLangInvokeAccess().generateHolderClasses(lineStream);
        int size = result.size();
        Object[] retArray = new Object[size * 2];
        int index = 0;
        for (Map.Entry<String, byte[]> entry : result.entrySet()) {
            retArray[index++] = entry.getKey();
            retArray[index++] = entry.getValue();
        };
        return retArray;
    }

    private static native void dumpClassList(String listFileName);
    private static native void dumpDynamicArchive(String archiveFileName);

    private static boolean containsExcludedFlags(String testStr) {
       return testStr.contains("-XX:DumpLoadedClassList=")     ||
              testStr.contains("-XX:+DumpSharedSpaces")        ||
              testStr.contains("-XX:+DynamicDumpSharedSpaces") ||
              testStr.contains("-XX:+RecordDynamicDumpInfo");
    }
   
    /**
    * called from jcmd VM.cds to dump static or dynamic shared archive
    * @param isStatic indicates dump static archive of dynnamic archive.
    * @param fileName user input archive name, can be null.
    */
    private static void dumpSharedArchive(boolean isStatic, String fileName) throws Exception {
        boolean DEBUG =  System.getProperty("CDS.Debug") == "true";
        String archiveFile =  fileName != null ? fileName :
            "java_pid" + ProcessHandle.current().pid() + (isStatic ? "_static.jsa" : "_dynamic.jsa");
        if (DEBUG) {
            System.out.println((isStatic ? "Static" : " Dynamic") + " dump to file " + archiveFile);
        }
        if (isStatic) {
            String listFile = archiveFile + ".classlist";
            dumpClassList(listFile);
            String jdkHome = System.getProperty("java.home");
            ArrayList<String> cmds = new ArrayList<String>();
            cmds.add(jdkHome + File.separator + "bin" + File.separator + "java"); // java
            cmds.add("-Xlog:cds");
            cmds.add("-Xshare:dump");
            cmds.add("-XX:SharedClassListFile=" + listFile);
            cmds.add("-XX:SharedArchiveFile=" + archiveFile);
            // All args in command line
            String[] vmArgs = getVMArguments();
            if (vmArgs != null) {
                for (String arg : vmArgs) {
                    if (arg != null && !containsExcludedFlags(arg)) { 
                        cmds.add(arg);
                    }
                }
            }

            if (DEBUG) {
                System.out.println("Static dump cmd: ");
                for (String s : cmds) {
                    System.out.print(s + " ");
                }
                System.out.println("");
            }

            // Do not take parent env which will cause dumping fail.
            Process proc = Runtime.getRuntime().exec(cmds.toArray(new String[0]),
                               new String[] {"EnvP=null"});
            if (DEBUG) {
                System.out.println("Dumping process " + proc.pid() + " Stdout: ");
                String line;
                InputStreamReader isr = new InputStreamReader(proc.getInputStream());
                BufferedReader rdr = new BufferedReader(isr);
                while((line = rdr.readLine()) != null) {
                    System.out.println(line);
                }

                System.out.println("Dumping process " + proc.pid() + " Stderr: ");
                isr = new InputStreamReader(proc.getErrorStream());
                rdr = new BufferedReader(isr);
                while((line = rdr.readLine()) != null) {
                    System.out.println(line);
                }
            }
            proc.waitFor();
        } else {
            dumpDynamicArchive(archiveFile);
        }
    }
}
