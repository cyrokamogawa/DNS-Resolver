import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.io.File;
//import java.io.Exception;
import java.util.*;
import java.lang.*;
import java.net.URL;
import java.io.*;

import java.math.BigInteger;
public class Resolver 
{
	public static void main(String[] args) throws Exception 
	{
		boolean mflag = false;

		try 
		{	
			if (args[0].equals("-m")) // Mail flag on
			{
				System.out.println("Mail exchange flag on");
				mflag = true;
			}
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			System.out.println("Usage: java Resolver <optional mail flag> server");
			return;
		}

		// Read in the root-servers.txt file
		URL path = Resolver.class.getResource("root-servers.txt");	
		File file = new File(path.getFile());
		BufferedReader reader = new BufferedReader(new FileReader(file));
		
		String [] rootServers = new String[100];
		
		Scanner input = null;
		try
		{
			input = new Scanner(file);
			while (input.hasNextLine())
			{ // Add the root servers from the TXT file to an array
				for (int i = 0; i < rootServers.length; i++)
				{
					rootServers[i] = input.nextLine();
				}
			}
		}
		catch (NoSuchElementException e) 
		{
		//	System.exit(1);
		}
		
		DatagramSocket sock = new DatagramSocket();
		sock.setSoTimeout(5000); // set timeout to 5000ms (i.e. 5 seconds)

		// construct a query and set that request
		ByteBuffer bb;
		String hostname = "";
		if (mflag == true){
			hostname = args[1];
			bb = constructQuery(hostname, mflag);
		}
		else{
			hostname = args[0];
			bb = constructQuery(hostname, mflag);
		}
	
		// Iterate over the root servers and query each one	
		for (int i = 0; i < rootServers.length; i++)
		{
			String serverIP = rootServers[i]; 
			// Using InetAddress.getByName in this project is only allowed if the
			// input is an IP address (i.e. don't give it "www.foo.com")
			System.out.println("Go to root server: " + rootServers[i]);
			DatagramPacket request = new DatagramPacket(bb.array(), 
													bb.position(),
													InetAddress.getByName(serverIP), 
													53);
	
	
			sock.send(request); // Send the request for the root server
				
			getResponse(sock, mflag, hostname, rootServers);
			
			if (serverIP == null) { // Reached EOF, no response from any queried server
				System.out.println("*** NO RESPONSE ***");
				System.exit(1);
			}
		}
	}

	/**
	 * Converts our Java string (e.g. www.sandiego.edu) to the DNS-style
	 * string (e.g. (3)www(8)sandiego(3)edu(0).
	 *
	 * @param s The string you want to convert.
	 * @returns Buffer containing the converted string.
	 */
	public static ByteBuffer stringToNetwork(String s) {
		ByteBuffer bb = ByteBuffer.allocate(s.length()+2);
		bb.order(ByteOrder.BIG_ENDIAN);

		String[] parts = s.split("\\.");
		for (String p : parts) {
			bb.put((byte)p.length());
			bb.put(p.getBytes());
		}

		bb.put((byte)0);

		return bb;
	}

	/**
	 * Converts a DNS-style string (e.g. (3)www(8)sandiego(3)edu(0) to the normal
	 * Java (e.g. www.sandiego.edu).
	 * This supports DNS-style name compression (see RFC 1035, Section 4.1.4).
	 *
	 * @param message The entire DNS message, in ByteBuffer format.
	 * @param start The location in the message buffer where the network
	 * 				string starts.
	 * @param sb StringBuilder to construct the string as we are reading the
	 * 				message.
	 * @return Location in message immediately after the string.
	 */
	private static int getStringFromDNS(ByteBuffer message, int start, StringBuilder sb) {
		int position = start;

		byte currByte = message.get(position);
		position += 1;

		while (currByte != 0) {
			byte length = currByte;
			byte wtf = (byte)((length >> 6) & 3);

			// If length starts with "11" then this is a compressed name
			//if (wtf == 3) {
			if ((length >> 6 & 3) == 3) {
				// next byte will contain rest of pointer
				byte nextByte = message.get(position); 
				int offset = (length & 0x3F) << 8;
				int nb = nextByte;
				nb = nb & 0xFF;
				offset |= nb;

				getStringFromDNS(message, offset, sb);
				return position + 1;
			}

			for (int i = 0; i < length; i++) {
				byte c = message.get(position);
				sb.append((char)c);
				position += 1;
			}
			sb.append('.');

			currByte = message.get(position);
			position += 1;
		}

		sb.deleteCharAt(sb.length()-1);

		return position;
	}

	/**
	 * Constructs a DNS query for hostname's Type A record.
	 *
	 * @param hostname The host we are trying to resolve
	 * @param mflag Indicates whether we are querying a mail server
	 * @return A ByteBuffer with the query
	 */
	public static ByteBuffer constructQuery(String hostname, boolean mflag) {
		ByteBuffer bb = ByteBuffer.allocate(5000);

		// Network order is BIG_ENDIAN (opposite of what x86 uses)
		bb.order(ByteOrder.BIG_ENDIAN);

		// Header
		// First 2 bytes: ID
		Random r = new Random();
		short s = (short) r.nextInt();
		bb.putShort(s); 
	
		// Next 12 bits: Flags, Opcode, Rcode	
		bb.putShort((short)0); // flags (set to make an iterative request)
	
		// Next 8 bytes:	
		// 1 question, no answers, no auth, or other records
		bb.putShort((short)1); // num questions
		bb.putShort((short)0); // num answers
		bb.putShort((short)0); // num auth  NS
		bb.putShort((short)0); // num other  Additional
		// End header
		
		// Body
		// Create and add the host name
		ByteBuffer nameString = Resolver.stringToNetwork(hostname);
		bb.put(nameString.array(), 0, nameString.position());
		if (mflag == false)
		{
			bb.putShort((short)1); // query type == 1 (i.e. Type A)
		}
		else
		{
			bb.putShort((short)15); // query type == 15 (i.e. Type MX)
		}
		bb.putShort((short)1); // class: INET
		return bb;
	}
	


	/** 
	 * Get the response from the queried server
	 *
	 * @param sock The socket that we will be exchanging data over
	 * @param mflag Indicates whether we are querying a mail server
	 * @param hostname The host we are trying to resolve
	 * @param rootServers The array of root server IPs
	 */ 
	public static void getResponse(DatagramSocket sock, boolean mflag, String hostname, String[] rootServers) throws Exception
	{
		ByteBuffer response_bb = ByteBuffer.allocate(5000);

		DatagramPacket response = new DatagramPacket(response_bb.array(),
													response_bb.capacity());
		try { // Try to receive a response
			sock.receive(response);
		} catch (SocketTimeoutException ste) { // Socket timed out
			System.out.println("*** TIMED OUT! ***");
			return;
		} catch (Exception e) {
			System.out.println("Error receiving: " + e);
			return;
		}

		// Now you'll need to interpret the response by reading the
		// response_bb and interpretting the values you find there.

		StringBuilder sb = new StringBuilder();
		short answerRRs, authorityRRs, additionalRRs;

		// Set variables to represent various positions in the buffer
		response_bb.position(6);
		answerRRs = response_bb.getShort();
		response_bb.position(8);
		authorityRRs = response_bb.getShort();
		response_bb.position(10);
		additionalRRs = response_bb.getShort();
	
		// Create arrays to store name servers and IP addresses	
		String[] authNameServers = new String[authorityRRs];
		int[] indexOfNS = new int[authorityRRs];
		int[] indexOfAdd = new int[additionalRRs];
		String[] ipAddresses = new String[additionalRRs]; 
		String[] additionalNameServers = new String[additionalRRs];

		System.out.println("answerRRs: " + answerRRs);
		System.out.println("authorityRRs: " + authorityRRs);
		System.out.println("additionalRRs: " + additionalRRs + "\n");
	
		if(answerRRs == 0)
		{ // If no answer, retrieve the information for the next query
			authNameServers = getAuthNames(authorityRRs, response_bb, sb, indexOfNS, authNameServers);
			ipAddresses = getAuthIPs(additionalRRs, indexOfAdd,  response_bb, indexOfNS, authorityRRs, sb, additionalNameServers, ipAddresses);
			reQuery(authorityRRs, additionalRRs, additionalNameServers, authNameServers, ipAddresses, mflag, hostname, sock);
			getResponse(sock, mflag, hostname, rootServers);
		}
		else if(answerRRs >= 1)
		{ // There is an answer, handle accordingly 
			getAnswer(response_bb, sb, hostname, mflag, rootServers);
		}
	}

	/**
	 * Retrieve IP address in the answer, or handle a CNAME or MX type
	 *
	 * @param response_bb The response ByteBuffer
	 * @param sb The StringBuilder that contains the nameserver
	 * @param hostname The host we are trying to resolve
	 * @param mflag Indicates whether we are querying a mail server
	 * @param rootServers The array of root server IPs
	 */ 
	public static void getAnswer(ByteBuffer response_bb, StringBuilder sb, String hostname, boolean mflag, String[] rootServers) throws Exception
	{
		// Set the index of the type
		int indexOfType = getStringFromDNS(response_bb, 12, sb) + 7;
		
		if(response_bb.get(indexOfType) == 5)
		{ // Type = CNAME
			int new_offset = 9;
			DatagramSocket CNAME_sock = new DatagramSocket();
			handleCNAMEorMX(sb, response_bb, indexOfType, new_offset, hostname, mflag, CNAME_sock, rootServers);
		}
		else if(response_bb.get(indexOfType) == 15)
		{ // Type = MX
			int new_offset = 11;
			DatagramSocket MX_sock = new DatagramSocket();
			handleCNAMEorMX(sb, response_bb, indexOfType, new_offset, hostname, mflag, MX_sock, rootServers);
		}
		else
		{ // Type = A (because types AAAA and SOA are handled elsewhere)
			int indexOfAnswer = getStringFromDNS(response_bb, 12, sb) + 16;
			String answer = formIPAddress(response_bb,indexOfAnswer - 10);
			System.out.println("ANSWER: " + hostname + ": " + answer);	
			System.exit(0);
		}
	}

	/**
	 * Handle a CNAME or MX type by reading and querying the new hostname
	 *
	 * @param sb The StringBuilder that contains the nameserver
	 * @param response_bb The response ByteBuffer
	 * @param indexOfType The index of the type that we will be checking
	 * @param new_offset The byte offset to handle CNAME vs MX
	 * @param hostname The host we are trying to resolve
	 * @param mflag Indicates whether we are querying a mail server
	 * @param new_sock The socket to handle CNAME vs MX
	 * @param rootServers The array of root server IPs
	 */
public static void handleCNAMEorMX(StringBuilder sb, ByteBuffer response_bb, int indexOfType, int new_offset, String hostname, boolean mflag, DatagramSocket new_sock, String[] rootServers) throws Exception
	{ 
		ByteBuffer bb = ByteBuffer.allocate(5000);
		sb.delete(0, sb.length());
		getStringFromDNS(response_bb, indexOfType + new_offset, sb);
		hostname = sb.toString(); mflag = false;
		System.out.println("Redirect to: " + hostname);
		bb = constructQuery(hostname, mflag);
		
		new_sock.setSoTimeout(5000);
		
		// Restart the query with a new hostname	
		for(int i = 0; i < rootServers.length; i++)
		{
			String serverIP = rootServers[0];
			DatagramPacket request = new DatagramPacket(bb.array(),
														bb.position(),
														InetAddress.getByName(serverIP),
														53);
			new_sock.send(request);
			getResponse(new_sock, mflag, hostname, rootServers);
		}		
	}
	
	/**
	 * Retrieve a list of the Authority nameservers 
	 *
	 * @param authorityRRs The number of authority records
	 * @param response_bb The response ByteBuffer
	 * @param sb The StringBuilder that contains the nameserver
	 * @param indexOfNS The index of the nameserver
	 * @param authNameServers An array to store the nameservers
	 * @return An array of nameservers
	 */
	public static String[] getAuthNames(int authorityRRs, ByteBuffer response_bb, StringBuilder sb, int[] indexOfNS, String[] authNameServers)
	{
		for(int i = 0; i < authorityRRs; i++)
		{ // Iterate over the Authority section to get NameServers 
			if(i == 0)
			{ // The first case is special
				int temp = getStringFromDNS(response_bb, 12, sb);
				sb.delete(0, sb.length());
				indexOfNS[0] = getStringFromDNS(response_bb, temp + 16, sb);
				authNameServers[0] = sb.toString();
				sb.delete(0, sb.length());
			}
			else
			{ // Get the rest of the NameServers 
				indexOfNS[i] = getStringFromDNS(response_bb, indexOfNS[i-1] + 12, sb);
				authNameServers[i] = sb.toString();
				sb.delete(0, sb.length());
			}
		}
		// authNameServers is now filled with NameServers
		return authNameServers;
	}

	/**
	 * Retrieve IP address in the answer, or handle a CNAME or MX type
	 *
	 * @param additionalRRs The number of Additional records
	 * @param indexOfAdd The index of the Additional IP address
	 * @param response_bb The response ByteBuffer
	 * @param indexOfNS The index of the nameserver
	 * @param authorityRRs The number of authority records
	 * @param sb The StringBuilder that contains the nameserver
	 * @param additionalNameServers An array to store the Additional
	 * nameservers
	 * @param ipAddresses An array to store the IP addresses
	 * @return A string array of IP addresses
	 */
	public static String[] getAuthIPs(int additionalRRs, int[] indexOfAdd, ByteBuffer response_bb, int[] indexOfNS, int authorityRRs, StringBuilder sb, String[] additionalNameServers, String[] ipAddresses)
	{
		if(authorityRRs == 1 && additionalRRs == 0){ // SOA --> exit
			System.out.println("SOA! Invalid HostName");
			System.exit(1);
		}
			
		int offset = 16;
		for(int i = 0; i < additionalRRs; i++)
		{ // Iterate over the Additional section to get IPs
			if(i == 0)
			{ // The first case is special
				indexOfAdd[0] = getStringFromDNS(response_bb, indexOfNS[authorityRRs- 1], sb);
				sb.delete(0, sb.length());
				getStringFromDNS(response_bb, indexOfAdd[0] - 2, sb);
				additionalNameServers[0] = sb.toString();
						
				short type = response_bb.getShort(indexOfAdd[0]);
				if(type == 1)
				{ // Type A --> Grab the IP
					ipAddresses[0] = formIPAddress(response_bb, indexOfAdd[0]);
					offset = 16;
				}
				else if (type == 28) 
				{ // Type AAAA --> Ignore, adjust offset
					ipAddresses[0] = "";
					offset = 28;	
				}
			}
			else
			{ // Get the rest of the IPs
				indexOfAdd[i] = indexOfAdd[i-1] + offset;
				sb.delete(0, sb.length());
				getStringFromDNS(response_bb, indexOfAdd[i] - 2, sb);
				additionalNameServers[i] = sb.toString();
											
				short type = response_bb.getShort(indexOfAdd[i]);
				if(type == 1)
				{ // Type A --> Grab the IP
					ipAddresses[i] = formIPAddress(response_bb, indexOfAdd[i]);
					offset = 16;
				}
				else if (type == 28) 
				{ // Type AAAA --> Ignore, adjust offset
					ipAddresses[i] = "";
					offset = 28;
				}
			}
			// ipAddresses now filled with IPs
		}
		return ipAddresses;
	}

// Put exceptions in try-catch blocks and add 2 to the index

	/**
	 * Retrieve IP address in the answer, or handle a CNAME or MX type
	 *
	 * @param authorityRRs The number of Authority records
	 * @param additionalRRs The number of Additional records
	 * @param additionalNameServers An array to store the Additional
	 * @param authNameServers An array to store the Authority nameservers 
	 * @param ipAddresses An array to store the IP addresses
	 * @param sb The StringBuilder that contains the nameserver
	 * nameservers
	 * @param mflag Indicates whether we are querying a mail server
	 * @param hostname The host we are trying to resolve
	 * @param sock The socket over which to communicate 
	 */
	public static void reQuery(int authorityRRs, int additionalRRs, String[] additionalNameServers, String[] authNameServers, String[] ipAddresses, boolean mflag, String hostname, DatagramSocket sock) throws Exception
	{
		ByteBuffer bb = ByteBuffer.allocate(5000);
		// Iterate over the Authority records and the Additional records
		// to compare the Additional nameservers to the Authority nameservers,
		// in order to confirm that the Additional nameservers are authorities 
		for(int i = 0; i < authorityRRs; i++)
		{ // Iterate over the Authority records
			for(int j = 0; j < additionalRRs; j++)
			{ // Iterate over the Additional records
				if(additionalNameServers[j].equals(authNameServers[i]))
				{ // Check for matches
					if(ipAddresses[j] != "")
					{ // If not null, print out the next destination and go there
						System.out.println("Go to: " + additionalNameServers[j] + " " + ipAddresses[j]);
						bb = constructQuery(hostname, mflag);
						DatagramPacket request = new DatagramPacket(bb.array(), 
													bb.position(),
													InetAddress.getByName(ipAddresses[j]), 
													53);
						sock.send(request);
					}
				}
			}
		}
	}

	/**
	 * Form the IP address found at the given starting index
	 *
	 * @param response_bb The response ByteBuffer
	 * @param start The starting index of the IP address
	 * @return The IP address in String form 
	 */	
	public static String formIPAddress(ByteBuffer response_bb, int start)
	{
		String ipAddress = "";
		for (int i = 10; i < 13; i++)
		{
			ipAddress += Integer.toString(unsignedToBytes(response_bb.get(start + i)));
			ipAddress += ".";
		}
		ipAddress += Integer.toString(unsignedToBytes(response_bb.get(start + 13)));
		return ipAddress;
	}	

	/**
	 * Converts the byte to an unsigned integer 
	 *
	 * @param b The byte given
	 * @return An integer representing the byte 
	 */
	public static int unsignedToBytes(byte b) 
	{
		return b & 0xFF;
	}	
}
