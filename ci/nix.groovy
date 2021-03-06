/**
 * Arguments:
 *  - pure - Use --pure mode with Nix for more deterministic behaviour
 *  - args - Map of arguments to provide to --argstr
 *  - keepEnv - List of env variables to keep even in pure mode
 **/
def shell(Map opts = [:], String cmd) {
  def defaults = [
    pure: true,
    args: ['target': env.TARGET ? env.TARGET : 'default'],
    keepEnv: ['LOCALE_ARCHIVE_2_27'],
    sandbox: true,
  ]
  /* merge defaults with received opts */
  opts = defaults + opts
  /* previous merge overwrites the array */
  opts.keepEnv = (opts.keepEnv + defaults.keepEnv).unique()
  /* not all targets can use a pure build */
  if (env.TARGET in ['windows', 'ios']) {
    opts.pure = false
  }
  sh("""
      set +x
      . ~/.nix-profile/etc/profile.d/nix.sh
      set -x
      nix-shell --run \'${cmd}\' ${_getNixCommandArgs(opts, true)}
  """)
}

/**
 * Arguments:
 *  - pure - Use --pure mode with Nix for more deterministic behaviour
 *  - link - Bu default build creates a `result` directory, you can turn that off
 *  - conf - Map of config values to provide to --arg config
 *  - args - Map of arguments to provide to --argstr
 *  - attr - Name of attribute to use with --attr flag
 *  - keepEnv - List of env variables to pass through to Nix build
 *  - safeEnv - Name of env variables to pass securely through to Nix build (they won't get captured in Nix derivation file)
 *  - sandbox - If build process should run inside of a sandbox
 *  - sandboxPaths - List of file paths to make available in Nix sandbox
 **/
def build(Map opts = [:]) {
  def defaults = [
    pure: true,
    link: true,
    args: ['target': env.TARGET],
    conf: [:],
    attr: null,
    keepEnv: [],
    safeEnv: [],
    sandbox: true,
    sandboxPaths: [],
  ]
  /* merge defaults with received opts */
  opts = defaults + opts
  /* Previous merge overwrites the array */
  opts.args = defaults.args + opts.args
  opts.keepEnv = (opts.keepEnv + defaults.keepEnv).unique()

  def nixPath = sh(
    returnStdout: true,
    script: """
      set +x
      . ~/.nix-profile/etc/profile.d/nix.sh
      set -x
      nix-build ${_getNixCommandArgs(opts, false)}
    """
  ).trim()
  /* if not linking, copy results, but only if there's just one path */
  if (!opts.link && nixPath && !nixPath.contains('\n')) {
    copyResults(nixPath)
  }
  return nixPath
}

private def copyResults(path) {
  def resultsPath = "${env.WORKSPACE}/result"
  sh "rm -fr ${resultsPath}"
  sh "mkdir -p ${resultsPath}"
  sh "cp -fr ${path}/* ${resultsPath}/"
  sh "chmod -R 755 ${resultsPath}"
}

private makeNixBuildEnvFile(Map opts = [:]) {
  File envFile = File.createTempFile("nix-env", ".tmp")
  if (!opts.safeEnv.isEmpty()) {
    // Export the environment variables we want to keep into a temporary script we can pass to Nix and source it from the build script
    def exportCommandList = opts.safeEnv.collect { envVarName -> """
      echo \"export ${envVarName}=\\\"\$(printenv ${envVarName})\\\"\" >> ${envFile.absolutePath}
    """ }
    def exportCommands = exportCommandList.join("")
    sh """
      ${exportCommands}
      chmod u+x ${envFile.absolutePath}
    """

    opts.args = opts.args + [ 'secrets-file': envFile.absolutePath ]
    opts.sandboxPaths = opts.sandboxPaths + envFile.absolutePath
  }

  return envFile
}

private def _getNixCommandArgs(Map opts = [:], boolean isShell) {
  def keepFlags = []
  def entryPoint = "\'${env.WORKSPACE}/shell.nix\'"
  if (!isShell || opts.attr != null) {
    entryPoint = "\'${env.WORKSPACE}/default.nix\'"
  }
  /* don't let nix.conf control sandbox status */
  def extraSandboxPathsFlag = "--option sandbox ${opts.sandbox}"

  if (isShell) {
    keepFlags = opts.keepEnv.collect { var -> "--keep ${var} " }
  } else {
    def envVarsList = opts.keepEnv.collect { var -> "${var}=\"${env[var]}\";" }
    keepFlags = ["--arg env \'{${envVarsList.join("")}}\'"]

    /* Export the environment variables we want to keep into
     * a Nix attribute set we can pass to Nix and source it from the build script */
    def envFile = makeNixBuildEnvFile(opts)
    envFile.deleteOnExit()
  }

  def configFlag = ''
  def argsFlags = opts.args.collect { key,val -> "--argstr ${key} \'${val}\'" }
  def attrFlag = ''
  if (opts.attr != null) {
    attrFlag = "--attr '${opts.attr}'"
  }
  if (opts.conf != null && opts.conf != [:]) {
    def configFlags = opts.conf.collect { key,val -> "${key}=\"${val}\";" }
    configFlag = "--arg config \'{${configFlags.join('')}}\'"
  }
  if (opts.sandboxPaths != null && !opts.sandboxPaths.isEmpty()) {
    extraSandboxPathsFlag += " --option extra-sandbox-paths \"${opts.sandboxPaths.join(' ')}\""
  }

  return [
    opts.pure ? "--pure" : "",
    opts.link ? "" : "--no-out-link",
    configFlag,
    keepFlags.join(" "),
    argsFlags.join(" "),
    extraSandboxPathsFlag,
    attrFlag,
    entryPoint,
  ].join(" ")
}

def prepEnv() {
  if (env.TARGET in ['linux', 'windows', 'android']) {
    def glibcLocales = sh(
      returnStdout: true,
      script: """
        . ~/.nix-profile/etc/profile.d/nix.sh && \\
        nix-build --no-out-link '<nixpkgs>' -A glibcLocales
      """
    ).trim()
    /**
     * This is a hack to fix missing locale errors.
     * See:
     * - https://github.com/NixOS/nixpkgs/issues/38991
     * - https://qiita.com/kimagure/items/4449ceb0bda5c10ca50f
     **/
    env.LOCALE_ARCHIVE_2_27 = "${glibcLocales}/lib/locale/locale-archive"
  }
}

return this
