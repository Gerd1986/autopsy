/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.casemodule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Runs the bundled, read-only NAND cleanup before registering a derived image. */
final class NandPreprocessor {

    private NandPreprocessor() {
    }

    static Path preprocess(Path input, Path config, Path exportDirectory, Path script,
            List<String> python, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (!Files.isRegularFile(input) || !Files.isReadable(input)
                || !Files.isRegularFile(config) || !Files.isReadable(config)) {
            throw new IOException("NAND preprocessing requires a readable dump and JSON configuration.");
        }
        if (script == null || !Files.isRegularFile(script)) {
            throw new IOException("Bundled NAND preprocessor is missing. Reinstall this Autopsy build.");
        }
        checkCancelled(cancelled);
        Path base = exportDirectory.resolve("nand-preprocessed");
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "dump-");
        Path output = directory.resolve("clean.img");
        Path log = directory.resolve("preprocessor.log");
        Path profile = directory.resolve("device-profile.json");
        Files.copy(config, profile);
        List<String> command = new ArrayList<>(python);
        command.add(script.toAbsolutePath().toString());
        command.add(input.toAbsolutePath().toString());
        command.add("--profile");
        command.add(profile.toAbsolutePath().toString());
        command.add("--output");
        command.add(output.toAbsolutePath().toString());
        command.add("--ecc");
        command.add("on");
        command.add("--uncorrectable");
        command.add("fail");
        Process process = null;
        boolean succeeded = false;
        try {
            checkCancelled(cancelled);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            // Never write bytecode to the installed application directory.
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            process = builder.start();
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
            }
            checkCancelled(cancelled);
            if (process.exitValue() != 0) {
                throw new IOException("NAND preprocessing failed (exit " + process.exitValue()
                        + "). No data source was added. Details: " + log);
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0
                    || !Files.isRegularFile(directory.resolve("clean.img.report.json"))) {
                throw new IOException("NAND preprocessing did not produce a complete image and report. Details: " + log);
            }
            if (hasUbiHeader(output)) {
                Path renamed=directory.resolve("clean.ubi");
                Path oldReport=directory.resolve("clean.img.report.json");
                String report=Files.readString(oldReport, java.nio.charset.StandardCharsets.UTF_8);
                String original="\"output\": "+jsonString(output.toAbsolutePath().toString());
                if(!report.contains(original))throw new IOException("NAND report output path does not match the generated image.");
                report=report.replace(original,"\"output\": "+jsonString(renamed.toAbsolutePath().toString()));
                Files.move(output,renamed);
                output=renamed;
                Files.writeString(directory.resolve("clean.ubi.report.json"),report,
                        java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.delete(oldReport);
                Files.writeString(log,"\nAutopsy detected a CRC-valid UBI header; final image: "+output+"\n",
                        java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.APPEND);
            }
            checkCancelled(cancelled);
            succeeded = true;
            return output;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                // The child must stop writing before incomplete files are removed.
                boolean interrupted = Thread.interrupted();
                while (true) {
                    try {
                        process.waitFor();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!succeeded) {
                // Preserve the config and log for diagnosis; never leave an incomplete image.
                Files.deleteIfExists(output);
                Files.deleteIfExists(directory.resolve("clean.img"));
                Files.deleteIfExists(directory.resolve("clean.ubi"));
                Files.deleteIfExists(directory.resolve("clean.img.report.json"));
                Files.deleteIfExists(directory.resolve("clean.ubi.report.json"));
                try (Stream<Path> paths = Files.list(directory)) {
                    for (Path path : (Iterable<Path>) paths::iterator) {
                        if (path.getFileName().toString().startsWith(".clean.")) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
        }
    }

    /** Conservative container naming: require a CRC-valid UBI v1 EC header at image start.
     * Layout: Linux drivers/mtd/ubi/ubi-media.h; CRC seed 0xffffffff, no final xor.
     * Whole-flash dumps with embedded/unknown filesystems retain the generic .img suffix.
     */
    private static boolean hasUbiHeader(Path image) throws IOException {
        byte[] header;
        try(java.io.InputStream in=Files.newInputStream(image)){header=in.readNBytes(64);}
        if(header.length!=64)return false;
        java.nio.ByteBuffer h=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.BIG_ENDIAN);
        if(h.getInt(0)!=0x55424923 || header[4]!=1)return false;
        for(int i=5;i<8;i++)if(header[i]!=0)return false;
        for(int i=28;i<60;i++)if(header[i]!=0)return false;
        long vid=Integer.toUnsignedLong(h.getInt(16)),data=Integer.toUnsignedLong(h.getInt(20));
        if(vid<64 || data<vid+64 || data>=Files.size(image))return false;
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(header,0,60);
        return ((~crc.getValue())&0xffffffffL)==Integer.toUnsignedLong(h.getInt(60));
    }

    // Matches Python json.dumps(..., ensure_ascii=True) used by the bundled report writer.
    private static String jsonString(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toCharArray()) {
            switch(c) {
                case '\\':out.append("\\\\");break;
                case '"':out.append("\\\"");break;
                case '\b':out.append("\\b");break;
                case '\f':out.append("\\f");break;
                case '\n':out.append("\\n");break;
                case '\r':out.append("\\r");break;
                case '\t':out.append("\\t");break;
                default:
                    if(c<32 || c>126)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("NAND preprocessing cancelled. No data source was added.");
        }
    }
}
