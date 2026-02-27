package attractor.llm.adapters

interface ProcessRunner {
    fun start(args: List<String>): Process
}

object DefaultProcessRunner : ProcessRunner {
    override fun start(args: List<String>): Process =
        ProcessBuilder(args).redirectErrorStream(false).start()
}
