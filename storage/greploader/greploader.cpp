// load data in 100-byte records for grep test
// loads 100 byte records
// records will be sequential ids starting from a starting key (default 0, use -k to change)

// -s [megabytes] to set number of megabytes to load (default: 10 megs)
// -p [percent] percent of docs to have pattern (default: .0092337)
// -c [xxx] three char pattern to put in positives (default: foo)
// -T load over thrift (default is to use copy/sync port)
// -P [port] (default: 9091)
// -q quiet,don't print anything (except timing if you asked for it)
// -t print load time
// -k [start key] first key to load (default: 0)

#include <string.h>
#include <sstream>
#include <iostream>
#include <iomanip>
#include <stdlib.h>
#include <signal.h>
#include <unistd.h>

#include <stdio.h>
#include <errno.h>
#include <netdb.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include <arpa/inet.h>

#define VERSTR "SCADSBDB0.1"

#ifndef MSG_MORE
#define MSG_MORE 0
#endif

#include <sys/time.h>
#include <time.h>

#include <math.h>

#include <protocol/TBinaryProtocol.h>
#include <transport/TSocket.h>
#include <transport/TTransportUtils.h>

#include "gen-cpp/Storage.h"

#include <iostream>

using namespace std;
using namespace apache::thrift;
using namespace apache::thrift::protocol;
using namespace apache::thrift::transport;

using namespace SCADS;

using namespace boost;

#define THRIFT_HOST "localhost"
#define THRIFT_PORT 9091

// timing stuff
static struct timeval start_time, cur_time, diff_time;

#define start_timing() if(timing) gettimeofday(&start_time,NULL)
#define end_timing() {				\
    if (timing) {				\
      gettimeofday(&cur_time,NULL);					\
      timersub(&cur_time,&start_time,&diff_time);			\
      cout << "Took "<<diff_time.tv_sec <<" seconds, and " << diff_time.tv_usec<<" microseconds."<<endl; \
    }									\
  }

static void printProgress(int perc, unsigned long curKey) {
  int i;
  int ne = perc/4; //using 25 spaces
  ostringstream oss;
  cout << "\rLoading: [";
  for (i = 0;i < ne;i++)
    cout << "=";
  for (i = (ne+1);i < 25;i++)
    cout << " ";
  cout << "] "<<perc<<"% (Key: "<<setfill('0')<<setw(10)<<curKey<<")";
  flush(cout);
}

void fillval(char* val,char* pattern, char pos) {
  for (int i = 0;i < 90;i++) {
    val[i] = ((random()%93)+32);
    if (val[i]==*pattern)
      val[i]++; // ensures we don't get pattern in anything we generate
  }
  val[90]='\0';
  if (pos) {
    int p = (random()%86);
    val[p]=*pattern;
    val[p+1]=*(pattern+1);
    val[p+2]=*(pattern+2);
  }
}

char node_data = 0;

int main(int argc,char* argv[]) {
  int size = 10;
  double perc = 0.0092337;
  char pattern[4];
  char key[11];
  char val[91];
  int opt,timing = 0;
  string keystring;
  string valstring;
  char loadType = 1;
  char quiet = 0;
  long startkey = 0;
  char stat;

  unsigned int s;
  FILE* f = fopen("/dev/urandom","r");
  fread(&s,sizeof(unsigned int),1,f);
  fclose(f);
  srand(s);


  sprintf(pattern,"%s","foo");
  int port = THRIFT_PORT;
  while ((opt = getopt(argc,argv, "Ttqs:p:c:P:k:")) != -1) {
    switch (opt) {
    case 's':
      size = atoi(optarg);
      break;
    case 'p':
      perc = atof(optarg);
      break;
    case 'P':
      port = atoi(optarg);
      break;
    case 'c':
      snprintf(pattern,4,"%s",optarg);
      break;
    case 'T':
      loadType = 0;
      break;
    case 'q':
      quiet = 1;
      break;
    case 't':
      timing = 1;
      break;
    case 'k':
      startkey = atol(optarg);
      break;
    default:
      fprintf(stderr,"Usage: %s [-Ttq] -s [size] -p [percent] -c [pattern] -P [port] -k [start key]\n",argv[0]);
      exit(EXIT_FAILURE);
    }
  }
  const char* host = (optind>=argc)?THRIFT_HOST:argv[optind];
  if (timing & !quiet)
    cout << "Will print timing info"<<endl;

  unsigned long records = size*10000;
  unsigned long rlim = records+startkey;
  int pos = ceil(perc/100*records);
  int m = (records/pos)-1;
  int pcount = 0;
  int waspos = 1;

  if (!quiet)
    cout << "loading "<<records<<" records. ("<<size<<" megs). "<<pos<<" positives with pattern: "<<pattern<<endl;


  if (loadType) { // do binary copy
    int sockfd, numbytes;
    int keylen = 10;
    int dlen = 90;
    struct addrinfo hints, *servinfo, *p;
    int rv;
    int nslen;
    char buf[12];
    string ns("greptest");
    sprintf(buf,"%i",port);

    if (!quiet)
      cout << "doing a binary load on copy/sync port"<<endl;

    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    if ((rv = getaddrinfo(host, buf, &hints, &servinfo)) != 0) {
      fprintf(stderr, "getaddrinfo: %s\n", gai_strerror(rv));
      return 1;
    }

    // loop through all the results and connect to the first we can
    for(p = servinfo; p != NULL; p = p->ai_next) {
      if ((sockfd = socket(p->ai_family, p->ai_socktype,
			 p->ai_protocol)) == -1) {
	perror("client: socket");
	continue;
      }

      if (connect(sockfd, p->ai_addr, p->ai_addrlen) == -1) {
	close(sockfd);
	perror("client: connect");
	continue;
      }

      break;
    }

    if (p == NULL) {
      fprintf(stderr, "client: failed to connect\n");
      return 2;
    }

    freeaddrinfo(servinfo); // all done with this structure

    if ((numbytes = recv(sockfd, buf, 11, 0)) == -1) {
      perror("Error receiving version string: ");
      return 2;
    }

    buf[numbytes] = '\0';

    if (strncmp(VERSTR,buf,11)) {
      fprintf(stderr,"Version strings didn't match");
      return 2;
    }

    // now send copy
    nslen = 0;
    if (send(sockfd,&nslen,1,MSG_MORE) == -1) {
      perror("Error sending copy op: ");
      return 2;
    }

    // now send our namespace
    nslen = ns.length();
    if (send(sockfd,&node_data,1,MSG_MORE) == -1) {
      perror("Error sending data type: ");
      return 2;
    }
    if (send(sockfd,&nslen,4,MSG_MORE) == -1) {
      perror("Error sending namespace length: ");
      return 2;
    }
    printf("Sending namespace: %s\n",ns.c_str());
    if (send(sockfd,ns.c_str(),nslen,MSG_MORE) == -1) {
      perror("Error sending namespace: ");
      return 2;
    }

    if (!quiet)
      printProgress(0,0);
    start_timing();
    // now send all our keys
    for (unsigned long cr = 0,i = startkey;i < rlim;i++,cr++) {
      if (!quiet && (cr % 1000 == 0))
	printProgress(((100*cr)/records),i);

      sprintf(key,"%010li",i);
      if (waspos) {
	fillval(val,pattern,0);
	waspos = 0;
      }
      if (cr%m==0 && pcount<pos) {
	// make pos here
	pcount++;
	fillval(val,pattern,1);
	waspos = 1;
      }
      if (send(sockfd,&node_data,1,MSG_MORE) == -1) {
	perror("Error sending data type: ");
	return 1;
      }
      if (send(sockfd,&keylen,4,MSG_MORE) == -1) {
	perror("Error sending keylen: ");
	return 1;
      }
      if (send(sockfd,key,keylen,MSG_MORE) == -1) {
	perror("Error sending key: ");
	return 1;
      }
      if (send(sockfd,&node_data,1,MSG_MORE) == -1) {
	perror("Error sending data type: ");
	return 1;
      }
      if (send(sockfd,&dlen,4,MSG_MORE) == -1) {
	perror("Error sending dlen: ");
	return 1;
      }
      if (send(sockfd,val,dlen,MSG_MORE) == -1) {
	perror("Error sending data: ");
	return 1;
      }
    }
    keylen = 0;
    if (send(sockfd,&node_data,1,MSG_MORE) == -1) {
      perror("Error sending data type: ");
      return 1;
    }
    if (send(sockfd,&keylen,4,MSG_MORE) == -1) {
      perror("Error sending final key: ");
      return 1;
    }
    if (!quiet) {
      printProgress(100,(rlim-1));
      cout<<endl<<"Waiting for success code."<<endl;
    }

    if ((numbytes = recv(sockfd, &stat, 1, 0)) == -1)
      cerr << "Could not read final status, your load might not have worked"<<endl;
    else if(!quiet)
      cout << "Done"<<endl;

    end_timing();
  } else {
    shared_ptr<TTransport> socket(new TSocket(host, port));
    shared_ptr<TTransport> transport(new TBufferedTransport(socket));
    shared_ptr<TProtocol> protocol(new TBinaryProtocol(transport));
    StorageClient client(protocol);

    Record r;
    r.__isset.key = true;
    r.__isset.value = true;

    if (!quiet)
      cout << "Loading through the thrift interface"<<endl;

    try {

      transport->open();

      if (!quiet)
	printProgress(0,0);

      start_timing();
      for (unsigned long cr = 0,i = startkey;i < rlim;i++,cr++) {
	if (!quiet & (cr % 1000 == 0))
	  printProgress(((100*cr)/records),i);

	sprintf(key,"%010li",i);
	if (waspos) {
	  fillval(val,pattern,0);
	  valstring.assign(val);
	  waspos = 0;
	}
	keystring.assign(key);
	if (i%m==0 && pcount<pos) {
	  // make pos here
	  pcount++;
	  fillval(val,pattern,1);
	  valstring.assign(val);
	  waspos = 1;
	}
	r.key = keystring;
	r.value = valstring;
	client.put("greptest",r);
      }
    } catch (TException &tx) {
      printf("ERROR: %s\n", tx.what());
    }
    if (!quiet) {
      printProgress(100,(rlim-1));
      cout<<endl;
    }
    end_timing();
  }
  if (!quiet)
    cout << "actual pos: "<<pcount<<endl;

}