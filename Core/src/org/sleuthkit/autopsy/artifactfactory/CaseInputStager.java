/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.sleuthkit.datamodel.*;

/** Private read-only working copies of case input; never extract into evidence. */
final class CaseInputStager implements AutoCloseable {
    private final Path root;
    private final Path input;
    CaseInputStager(AbstractFile source)throws Exception {
        root=Files.createTempDirectory("autopsy-wizard-case-");
        try {
            input=child(root,source.getName());copy(source,input,new HashSet<>());
            if(!source.isDir()&&source.getParent()!=null){
                for(Content related:source.getParent().getChildren()){
                    if(related instanceof AbstractFile&&!((AbstractFile)related).isDir()&&(related.getName().equals(source.getName()+"-wal")||related.getName().equals(source.getName()+"-shm")))copy((AbstractFile)related,child(root,related.getName()),new HashSet<>());
                }
            }
        }catch(Exception ex){close();throw ex;}
    }
    Path input(){return input;}
    private static Path child(Path parent,String name)throws IOException{
        if(name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0)throw new IOException("Nicht unterstützter Quelldateiname: "+name);
        return parent.resolve(name);
    }
    private static void copy(AbstractFile source,Path target,Set<Long> seen)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(!seen.add(source.getId()))return;
        if(source.isDir()){
            Files.createDirectory(target);
            for(Content entry:source.getChildren())if(entry instanceof AbstractFile&&!entry.getName().equals(".")&&!entry.getName().equals(".."))copy((AbstractFile)entry,child(target,entry.getName()),seen);
        }else{
            try(java.io.OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){
                byte[] bytes=new byte[1024*1024];long pos=0;
                while(pos<source.getSize()){if(Thread.currentThread().isInterrupted())throw new InterruptedException();int n=source.read(bytes,pos,Math.min(bytes.length,source.getSize()-pos));if(n<=0)throw new IOException("Quelldatei konnte nicht vollständig gelesen werden: "+source.getName());out.write(bytes,0,n);pos+=n;}
            }
        }
    }
    @Override public void close()throws IOException{
        if(!Files.exists(root))return;
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);}
    }
}
