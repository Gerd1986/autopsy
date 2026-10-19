/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.casemodule;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class MergeFilesHarness {
 public static void main(String[] args)throws Exception {
  if(args[0].equals("preview")){byte[] b=MergeFiles.preview(Path.of(args[1]),Long.parseLong(args[2]),Long.parseLong(args[3]),Boolean.parseBoolean(args[4]),Boolean.parseBoolean(args[5]));System.out.println(Base64.getEncoder().encodeToString(b));return;}
  List<Path> inputs=new ArrayList<>();for(int i=7;i<args.length;i++)inputs.add(Path.of(args[i]));AtomicBoolean cancel=new AtomicBoolean(args[6].equals("cancel"));
  long written=MergeFiles.merge(inputs,Path.of(args[1]),Long.parseLong(args[2]),args[3],Boolean.parseBoolean(args[4]),Boolean.parseBoolean(args[5]),(done,total)->{if(done>0&&args[6].equals("progress-cancel"))cancel.set(true);},cancel::get);
  System.out.println(written);
 }
}
