import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;

public class Server implements Runnable {   
    final ConcurrentHashMap<Integer, Socket> nicknames = new ConcurrentHashMap<Integer, Socket>();
    final ConcurrentHashMap<Integer, Socket> active = new ConcurrentHashMap<Integer, Socket>();
    final ConcurrentHashMap<Integer, PrintWriter> writers = new ConcurrentHashMap<Integer, PrintWriter>();
    private final int port = 4863;
    private final int webPort = 4864;
    static int groupId = 0;
    
    public static void main(String[] args) {
        Server s = new Server();
        s.run();
    }
    
    
    @Override
    public void run() {
        final ServerSocket serverSocket, webServerSocket;
        Socket webClient = null;
        Socket newClient = null;
        final ClientData d = new ClientData(nicknames, active, writers);
        int count = 0;

        // Open a server socket for the webserver and for the clients
        try {
            webServerSocket = new ServerSocket(webPort);
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            System.err.println("Could not open server socket.");
            e.printStackTrace();
            return;
        }
        

        //Asynchronously accept a connection from the web server to communicate about active clients
        new Thread(new WebConnectThread(webServerSocket, webClient, d)).start();
        
        // Keep accepting and serving clients 
        while (true) {
            try {
                // Wait for an incoming client connection
                System.err.println("Server waiting for incoming connections...");
                newClient = serverSocket.accept();
                System.err.println("Server received incoming connection from " + newClient.getInetAddress().toString());
            } catch (IOException e) {
                System.err.println("Server received IOException while listening for clients...");
                e.printStackTrace();
                break;
            }
            
            // Add client to data and notify webserver (if it exists)
            d.addSocket(count, newClient);
            
            new Thread(new WriterThread(count, d)).start();
            new Thread(new ReaderThread(count, d)).start();
            count += 1;
        }
        
    }
        // Encapsulates all data required by the reader/writer threads
        class ClientData {
            final ConcurrentHashMap<Integer, Socket> nicknames;
            final ConcurrentHashMap<Integer, Socket> active;
            final ConcurrentHashMap<Integer, PrintWriter> writers;

            Socket webClient;
            boolean hasWeb;
            PrintWriter p;
            
            ClientData(ConcurrentHashMap<Integer, Socket> nicknames,
                    ConcurrentHashMap<Integer, Socket> active,
                    ConcurrentHashMap<Integer, PrintWriter> writers) {
                 this.nicknames = nicknames;
                 this.active = active;
                 this.webClient = null;
                 this.p = null;
                 this.hasWeb = false;
                 this.writers = writers;
            }
                        
            private void addWeb(Socket webClient) {
                this.webClient = webClient;               
                this.hasWeb = true;
                try {
                    this.p = new PrintWriter(webClient.getOutputStream());
                } catch (IOException e) {
                    this.p = null;
                    return;
                }   
                pushToWebServer(); // Push starting set of data to webserver
            }

            private void disableWeb(Socket webClient) {
                this.webClient = null;
                this.hasWeb = false;
                this.p = null;
            }
            
            private void pushToWebServer() {
                    String ip;
                    String toWrite = "";
                    for (int nickname : this.nicknames.keySet()) {
                        ip = this.nicknames.get(nickname).getInetAddress().toString().substring(1); // omits '/' at the beginning of ip
                        toWrite += String.valueOf(nickname) + ":" + ip + ",";
                    }
                    if (toWrite.length() > 0) 
                        toWrite = toWrite.substring(0, toWrite.length() - 1);
                    p.print(toWrite);
                    p.flush();
            }

            void addSocket(int count, Socket s) {
                try {
                    writers.put(count, new PrintWriter(s.getOutputStream()));
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                nicknames.put(count, s);
                active.put(count, s);
                if (this.hasWeb)
                    pushToWebServer();
            }
            
            void deleteSocket(int s) {
                this.nicknames.remove(s);
                this.active.remove(s);
                writers.get(s).close();
                writers.remove(s);
                if (this.hasWeb)
                    pushToWebServer();
            }
        }
        
        
        class WebConnectThread implements Runnable {
            ServerSocket serverSocket;
            Socket client;
            ClientData d;

            WebConnectThread(ServerSocket webServerSocket, Socket webClient, ClientData d) {
                this.serverSocket = webServerSocket;
                this.client = webClient;
                this.d = d;
            }
            
            @Override
            public void run() {
                System.err.println("Server waiting for webserver connection...");
                try {
                    this.client = this.serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                System.err.println("Server received incoming web connection from " + this.client.getInetAddress().toString());
                this.d.addWeb(client);
                new Thread(new WebServerThread(this)).start();
            }
            
            
        }
        
        // Thread that communicates with the WebServer
        class WebServerThread implements Runnable {
            private Socket socket;
            private ClientData data;
            private ServerSocket serverSocket;
            
            public WebServerThread(WebConnectThread w) {
                this.socket = w.client;
                this.data = w.d;
                this.serverSocket = w.serverSocket;
            }
            
            @Override
            public void run() {
                BufferedReader inFromClient = null;
                String s = "";

                try {
                    inFromClient = new BufferedReader(new InputStreamReader (socket.getInputStream()));
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                
                // Listens to webserver to determine which clients to turn on and off and makes those changes 
                while (true) {
                    try {
                        // Read list of active clients from web server
                        s = inFromClient.readLine();
                        if (s == null) { // broken socket
                            throw new SocketException();
                        }
                        String[] pieces = s.split(",");
                        String[] subpieces;
                        int nickname;
                        boolean value;
                        // Update active set (for valid client nicknames provided)
                        for (String piece : pieces) {
                            subpieces = piece.split(":", 2);
                            nickname = Integer.valueOf(subpieces[0]);
                            value = (Integer.valueOf(subpieces[1]) > 0) ? true : false;
                            if (this.data.nicknames.containsKey(nickname)) {
                                if (value) 
                                    this.data.active.put(nickname, this.data.nicknames.get(nickname));
                                else 
                                    this.data.active.remove(nickname);
                            }
                        }
                    } catch (SocketException e) {
                        e.printStackTrace();
                        // Disable webserver connectivity
                        this.data.disableWeb(this.socket);
                        // Attempt to reconnect with webserver
                        new Thread(new WebConnectThread(serverSocket, socket, data)).start();
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }
                }
            }
            
        }
        
        class WriterThread implements Runnable {
            private final int nickname;
            private final Socket s;
            private final ClientData data;
            private final String ip;

            public WriterThread(int nickname, ClientData d) {
                this.nickname = nickname;
                this.data = d;
                this.s = d.nicknames.get(nickname);
                this.ip = this.s.getInetAddress().toString();
            }

            @Override
            public void run() {
                String command;
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                PrintWriter p;
                String groupId; 
                while (true) {
                    try {
                        //Read from stdin
                        command = reader.readLine();
                        groupId = String.valueOf(Server.groupId++); //identifier of this group photo
                        // Write out to all output streams
                        for (int w : this.data.active.keySet()) {
                            p = this.data.writers.get(w); //print writer
                            p.print(command + groupId);
                            p.flush();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        
        class ReaderThread implements Runnable {
            private final int nickname;
            private final Socket s;
            private final ClientData data;
            private final String ip;

            public ReaderThread(int nickname, ClientData d) {
                this.nickname = nickname;
                this.data = d;
                this.s = d.nicknames.get(nickname);
                this.ip = this.s.getInetAddress().toString();
            }

            @Override
            public void run() {
                BufferedReader reader;
				try {
					reader = new BufferedReader(new InputStreamReader(this.s.getInputStream()));
				} catch (IOException e1) {
					e1.printStackTrace();
					return;
				}
                OutputStream out;
                PrintWriter p;
                String command;
                String groupId;
                while (true) {
                    try {
                        command = reader.readLine(); // get input string from raspi
                        if (command == null) { // broken socket
                            System.err.println("Socket broken, terminating connection.");
                            throw new SocketException();
                        }
                        else if (!this.data.active.containsKey(nickname)) { //inactive raspi, ignore
                            continue;
                        }
                        else if (!((command.equals("Photo")) || (command.equals("Video")))) {
                            continue; //invalid command
                        }
                        System.err.println("Got command " + command + " from pi at " + ip);
                        groupId = String.valueOf(Server.groupId++);
                        //Write out to all sockets
                        for (int w : this.data.active.keySet()) {
                            p = this.data.writers.get(w); //print writers
                            p.print(command + groupId);
                            p.flush();
                        }
                    } catch (SocketException socketE) {
                        this.data.deleteSocket(nickname);
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    
                }
            }
        }
}
