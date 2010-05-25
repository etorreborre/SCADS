apt-get -y install jetty

mkdir -p /home/jetty/.ssh
cp /home/ubuntu/.ssh/authorized_keys /home/jetty/.ssh/
chown -R jetty /home/jetty/
chgrp -R jetty /home/jetty/

cat <<EOF > /etc/default/jetty
# Defaults for jetty see /etc/init.d/jetty for more

# change to 0 to allow Jetty to start
NO_START=0

# change to 'no' or uncomment to use the default setting in /etc/default/rcS 
VERBOSE=yes

# Run Jetty as this user ID (default: jetty)
# Set this to an empty string to prevent Jetty from starting automatically
JETTY_USER=jetty

# Listen to connections from this network host (leave empty to accept all connections)

#Uncomment to restrict access to localhost
#JETTY_HOST=$(uname -n)

# The network port used by Jetty
JETTY_PORT=8080

# Timeout in seconds for the shutdown of all webapps
#JETTY_SHUTDOWN=30

# Additional arguments to pass to Jetty    
#JETTY_ARGS=

# Extra options to pass to the JVM         
#JAVA_OPTIONS="-Xmx256m -Djava.awt.headless=true"

# Home of Java installation.
#JAVA_HOME=

# The first existing directory is used for JAVA_HOME (if JAVA_HOME is not
# defined in /etc/default/jetty). Should contain a list of space separated directories.
#JDK_DIRS="/usr/lib/jvm/default-java /usr/lib/jvm/java-6-sun"

# Java compiler to use for translating JavaServer Pages (JSPs). You can use all
# compilers that are accepted by Ant's build.compiler property.
#JSP_COMPILER=jikes

# Jetty uses a directory to store temporary files like unpacked webapps
#JETTY_TMP=/var/cache/jetty

# Jetty uses a config file to setup its boot classpath
#JETTY_START_CONFIG=/etc/jetty/start.config

# Default for number of days to keep old log files in /var/log/jetty/
#LOGFILE_DAYS=14
EOF

ln -s /var/lib/jetty/webapps /usr/share/java/webapps
update-rc.d jetty defaults
/etc/init.d/jetty start


