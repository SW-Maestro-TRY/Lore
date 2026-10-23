package com.lore.zzal.generation.client;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CustomPostprocessFailureTest {
 @TempDir Path dir;
 static class Process extends PythonPostProcessor {
  Process(String python){super(null,null,python,1,v->List.of());}
  void run(Path script,Path output)throws Exception{exec(script.toString(),output,output,List.of(),output.resolve("stdout.log"));}
 }
 @Test void customProcessKeepsSeparateStdoutStderrAndTypedOriginalCause()throws Exception{
  String python=System.getenv("LORE_CUSTOM_TEST_PYTHON");Assumptions.assumeTrue(python!=null);
  Path script=dir.resolve("custom_post.py");Files.writeString(script,"import sys\nprint('POSTPROCESS_CLIP')\nprint('synthetic error', file=sys.stderr)\nsys.exit(3)\n");
  assertThatThrownBy(()->new Process(python).run(script,dir)).isInstanceOfSatisfying(PostprocessFailureException.class,f->{assertThat(f.errorCode()).isEqualTo("CUSTOM_POSTPROCESS_CLIP");assertThat(f.stdout()).contains("POSTPROCESS_CLIP");assertThat(f.stderr()).contains("synthetic error");assertThat(f.getCause()).hasMessage("Postprocess exit 3");});
 }
}
