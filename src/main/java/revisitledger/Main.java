package revisitledger;
/** Process entry point; cooperative termination leaves staged reports uncommitted. */
public final class Main {
 private Main(){}
 public static void main(String[] args){
  Thread command=Thread.currentThread();
  Thread cleanup=new Thread(()->{command.interrupt();try{command.join(30000);}catch(InterruptedException e){Thread.currentThread().interrupt();}},"revisitledger-cleanup");
  Runtime.getRuntime().addShutdownHook(cleanup);
  int result=Cli.run(args,System.out,System.err);
  try{Runtime.getRuntime().removeShutdownHook(cleanup);}catch(IllegalStateException ignored){}
  if(!command.isInterrupted())System.exit(result);
 }
}
