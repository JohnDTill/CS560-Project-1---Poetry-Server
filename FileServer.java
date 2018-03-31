import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;

public class FileServer {
	public static void main(String[] args) throws IOException {

		//Default to single-thread mode if only a port number is provided
		boolean multithread_mode = false;
		
		if (args.length == 2) {
			//Allow a second command line argument for multithread mode
			multithread_mode = Boolean.parseBoolean(args[1]);
		}else if(args.length != 1){
			System.err.println("Usage: java FileServer <port> <multithread mode>");
			System.exit(1);
		}

		int portNumber = Integer.parseInt(args[0]);
		ServerSocket serverSocket = new ServerSocket(portNumber);

		//Build directories of static files in html once on startup
		generateHtmlDirectories("/Poems", "");

		boolean keep_running = true;
		while(keep_running){
			Socket clientSocket = serverSocket.accept();

			if( !multithread_mode ){
				//Resolve the connection in the main thread
				resolveClientConnection(clientSocket);
			}else{
				//Fork a new thread and continue to wait for connections
				Thread t1 = new Thread(new Runnable() {
					public void run() {
						resolveClientConnection(clientSocket);
					}
				});  
				t1.start();
			}
		}
	}
	
	private static void generateHtmlDirectories(String location, String parent){
		PrintWriter out = null;
		try{
			out = new PrintWriter("." + location + "/index.html");
		}catch (FileNotFoundException e){
			System.out.println("Failed to generate directory structure for " + "." + location + "/index.html");
		}
		
		//Generate a "directory.html" file
		File dir = new File("." + location);
		File[] directoryListing = dir.listFiles();
		
		out.println("<!DOCTYPE html>");
		out.println("<html lang=\"en\">");
		out.println("<body class=\"home\">");
		out.println("<a href=\"" + parent + "/index.html" + "\">Up One Level</a><br>"); //Provide a link to the parent folder
		out.println("<h2>Directory Contents</h2>");
		
		for (File child : directoryListing) {
			if( child.isDirectory() ){
				generateHtmlDirectories( location + "/" + child.getName(), location ); //Recursively generate child folder HTML directories
				out.println("<a href=\"" + location + "/" + child.getName() + "/index.html\">" + child.getName() + "</a><br>");
			}else{
				if( !child.getName().equals("index.html") ){ //Exclude "index.html" files from the listing
					out.println("<a href=\"" + location + "\\" + child.getName() + "\">" + child.getName() + "</a><br>");
				}
			}
		}
		
		out.println("</body>");
		out.println("</html>");
		out.close();
	}
	
	private static void resolveClientConnection(Socket clientSocket){
		try (  
			PrintWriter print_out =
				new PrintWriter(clientSocket.getOutputStream(), true);
			OutputStream byte_out = clientSocket.getOutputStream();
			BufferedReader in = new BufferedReader(
				new InputStreamReader(clientSocket.getInputStream()));
		) {
			String whole_message = "";
			String line = in.readLine();
			
			while(line!= null && !line.isEmpty()){
				whole_message += "\n" + line;
				line = in.readLine();
			}
			
			System.out.println(whole_message);
			
			Scanner scanner = new Scanner(whole_message);
			if(!scanner.hasNext()){
				return;
			}

			String http_command = scanner.next();
			
			if( http_command.equals("GET") ){
				handleGetRequest(whole_message, print_out, byte_out);
			}else{
				//Only allow HTTP GET messages from the client
				String response = "HTTP/1.1 405 Method Not Allowed";
				print_out.print( response );
				print_out.flush();
			}
		} catch (IOException e) {
			System.out.println("Exception caught while resolving client connection.");
			System.out.println(e.getMessage());
		}
	}
	
	private static void handleGetRequest(String http_request, PrintWriter print_out, OutputStream byte_out){
		Scanner scanner = new Scanner(http_request);
		String http_command = scanner.next();
		String resource = scanner.next();
		
		if( resource.charAt(0)!='/' ){
			//Disallow access to non-local files - requests must start with '/'
			print_out.print("HTTP/1.1 400 Bad Request");
			print_out.flush();
			return;
		}else if( resource.equals("/") ){
			convertResourceToResponse("/index.html", print_out, byte_out);
		}else if( resource.length() >= 19 && resource.substring(0,19).equals("/Forms/request_form") ){
			String form_data = resource.substring(20);
			respondToAuthorRequest(form_data, print_out, byte_out);
		}else if( resource.length() >= 19 && resource.substring(0,19).equals("/CGI/guest_addition") ){
			String form_data = resource.substring(20);
			respondToGuestAddition(form_data, print_out, byte_out);
		}else{
			try{
				resource = java.net.URLDecoder.decode(resource, "UTF-8");
			}catch (UnsupportedEncodingException e) {
				print_out.print("HTTP/1.1 500 Internal Server Error");
				print_out.flush();
				return;
			}
			convertResourceToResponse(resource, print_out, byte_out);
		}
	}
	
	private static void respondToAuthorRequest(String form_data, PrintWriter print_out, OutputStream byte_out){
		String[] components = form_data.split("=|&");
		String author = components[1];
			
		if( author.equals("") ){
			convertResourceToResponse("/Forms/RequestFormAuthorRequired.html", print_out, byte_out);
			return;
		}
		
		try{
			form_data = java.net.URLDecoder.decode(form_data, "UTF-8") + "\n";
			Files.write(Paths.get("./Forms/Suggestions.txt"), form_data.getBytes(), StandardOpenOption.APPEND);
		}catch (IOException e) {
			print_out.print("HTTP/1.1 500 Internal Server Error");
			print_out.flush();
			return;
		}
		
		convertResourceToResponse("/Forms/Confirmation.html", print_out, byte_out);
	}

	private static void respondToGuestAddition(String form_data, PrintWriter print_out, OutputStream byte_out){
		String[] components = form_data.split("=|&");

		if( components.length==1 ){
			convertResourceToResponse("/CGI/GuestAdditionError.html", print_out, byte_out);
			return;
		}

		String user = components[1];
		String output = "";

		try{
			user = java.net.URLDecoder.decode(user, "UTF-8") + "\n";

			//Run the CGI shell script and print its output to the PrintWriter
			Runtime r = Runtime.getRuntime();
			Process p = r.exec( new String[] {"./CGI/add_guest.sh", user} );
			p.waitFor();
			BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line = "";
			
			while ((line = b.readLine()) != null) {
				output += (line + "\r\n");
			}
			
			b.close();
		}catch (IOException e) {
			print_out.print("HTTP/1.1 500 Internal Server Error");
			print_out.flush();
			return;
		}catch (InterruptedException e) {
			print_out.print("HTTP/1.1 500 Internal Server Error");
			print_out.flush();
			return;
		}

		print_out.print(output);
		print_out.flush();
	}
	
	private static void convertResourceToResponse(String resource, PrintWriter print_out, OutputStream byte_out){
		byte[] resource_content;
		try{
			//Convert the requested resource to a byte array so it can be written to an OutputStream
			resource_content = Files.readAllBytes(Paths.get("." + resource));
		} catch (IOException e) {
			//Send an 404 error response if the resource does not exist
			print_out.print("HTTP/1.1 404 Not Found");
			print_out.flush();
			return;
		}
		
		String[] deliniated_resource = resource.split("\\.");
		String file_extension = deliniated_resource[1];;
		
		long millisec = new File("." + resource).lastModified();
		SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		String last_modified = dateFormat.format(new Date(millisec));
		
		if( file_extension.equals("txt") ){
			print_out.print( getHttpResponseHeader("text/enriched [RFC1896]", last_modified, resource_content.length) );
		}else if( file_extension.equals("jpg") ){
			print_out.print( getHttpResponseHeader("image/jpeg", last_modified, resource_content.length) );
		}else if( file_extension.equals("ico") ){
			print_out.print( getHttpResponseHeader("image/x-icon", last_modified, resource_content.length) );
		}else if( file_extension.equals("html") ){
			print_out.print( getHttpResponseHeader("text/html", last_modified, resource_content.length) );
		}else{
			//Send a 500 error if the resource extension is not whitelisted
			System.out.println("Unsupported extension requested");
			print_out.print("HTTP/1.1 500 Internal Server Error");
			print_out.flush();
			return;
		}
		print_out.flush();
		
		try{
			byte_out.write( Files.readAllBytes(Paths.get("." + resource)) );
			byte_out.flush();
		}catch(IOException e){
			print_out.print("Failure");
			System.out.println("Failure");
			print_out.flush();
		}
	}
	
	private static String getHttpResponseHeader(String content_type, String last_modified, int num_bytes){
		String response = "HTTP/1.1 200 OK\r\n";
		SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		response += "Date: " + dateFormat.format(Calendar.getInstance().getTime()) + "\r\n";
		response += "Server: Custom\r\n";
		response += "Last-Modified: " + last_modified + "\r\n";
		//Optional ETag is not implemented - would be used to improve caching
		response += "Accept-Ranges: bytes" + "\r\n";
		response += "Content-Length: " + num_bytes + "\r\n";
		response += "Connection: close" + "\r\n";
		response += "Content-Type: " + content_type + "\r\n";
		response += "\r\n"; //A blank line seperates header and body
		
		return response;
	}
}