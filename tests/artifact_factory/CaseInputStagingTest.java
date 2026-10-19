/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;
import java.nio.file.*;
import java.util.*;
import org.sleuthkit.datamodel.*;
public final class CaseInputStagingTest {
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("case-input-test-");SleuthkitCase sk=SleuthkitCase.newCase(root.resolve("case.db").toString());
  try{
   SleuthkitCase.CaseDbTransaction tx=sk.beginTransaction();LocalFilesDataSource ds;
   try{ds=sk.addLocalFilesDataSource("wizard-input-test","Evidence","UTC",tx);tx.commit();}catch(Exception ex){tx.rollback();throw ex;}
   VirtualDirectory folder=sk.addVirtualDirectory(ds.getId(),"folder");
   LocalFile main=null;
   for(String name:Arrays.asList("data.db","data.db-wal","data.db-shm")){
    Path src=root.resolve(name);Files.writeString(src,"original:"+name);
    LocalFile file=sk.addLocalFile(name,src.toString(),Files.size(src),0,0,0,0,true,TskData.EncodingType.NONE,folder);
    if(name.equals("data.db"))main=file;
   }
   Path copy;
   try(CaseInputStager staged=new CaseInputStager(main)){
    copy=staged.input();if(!Files.readString(copy).equals("original:data.db"))throw new AssertionError("Content mismatch");
    if(!Files.readString(Path.of(copy+"-wal")).equals("original:data.db-wal"))throw new AssertionError("WAL not staged");
    if(!Files.isRegularFile(Path.of(copy+"-shm")))throw new AssertionError("SHM not staged");
   }
   if(Files.exists(copy))throw new AssertionError("Temporary source not removed");
   try(CaseInputStager staged=new CaseInputStager(folder)){
    if(!Files.isDirectory(staged.input())||!Files.readString(staged.input().resolve("data.db")).equals("original:data.db"))throw new AssertionError("Folder staging failed");
   }
   if(!Files.readString(root.resolve("data.db")).equals("original:data.db"))throw new AssertionError("Evidence changed");
  }finally{sk.close();}
  System.out.println("PASS: real native case file/folder inputs, sibling WAL/SHM, exact source bytes and temporary cleanup");
 }
}
