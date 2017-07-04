import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * <p>This program reads json files which form a social network of users
 * that keeps dynamically changing and recording purchases made by these
 * users. It then calculates if purchases coming from a simulated streaming
 * json file are anomalous (where they are 3 standard deviations above the
 * mean of purchases in a D<sup>th</sup> degree network around the user and
 * exports these purchases to an output file.</p>
 * <p>The overall flow of the program is as follows:</p>
 * <ol>
 * <li>The {@link AnomalousPurchaseDetector#main(String[])}
 * function calls the readJsonStream() function for both
 * the batch and stream log files, with options to only check for
 * anomalies in the latter file.
 * <li>The {@link AnomalousPurchaseDetector#readJsonStream(InputStream, boolean)}
 * function reads the json files one line
 * at a time and tries to load the data into a JsonEvent object.
 * It then categorizes the type of data (4 types - D/T specification,
 * befriending, unfriending and purchasing). Appropriate functions
 * are then used to load the data into an {@link ArrayList} of
 * {@link User} objects (which also contain the {@link Purchase}
 * objects as an {@link ArrayList} object in each
 * {@link User}) where the index of the object also conveniently
 * serves as the user id.
 * <li>The {@link AnomalousPurchaseDetector#readJsonStream(InputStream, boolean)}
 * also calls the {@link AnomalousPurchaseDetector#checkForAnomaly(int, double, long)}
 * function for each encountered purchase when dealing with the
 * stream_log.json file.
 * <li>The {@link AnomalousPurchaseDetector#checkForAnomaly(int, double, long)}
 * function first forms a friend network
 * of the {@link AnomalousPurchaseDetector#D}<sup>th</sup> degree
 * with the help of a {@link UserNetworkBuilder} object
 * and gets the {@link AnomalousPurchaseDetector#T} most recent
 * purchases from this network (with
 * sorting performed by a custom {@link Comparator} object
 * ({@link Purchase.ComparePurchaseDates}) that implements
 * the sorting rule specified in the project).
 * <li>It then identifies if the purchase amount is anomalous with
 * the help of a {@link MeanSTDCutoff} object. If the purchase is found to
 * be anomalous, the data is written to the output file in the
 * appropriate json format.
 * </ol>
 */
public class AnomalousPurchaseDetector {
	/**
	 * <p>This enum constant determines if the Network calculation
	 * accounts for the time when users were befriended or not.
	 * The original problem description requires that the local
	 * network around a user be calculated based on the instantaneous
	 * list of friends that user has, and all transactions made
	 * by these users in the past are listed and sorted to find the
	 * {@link AnomalousPurchaseDetector#T} latest transactions for
	 * anomaly calculation. </p>
	 * <p>However, one can envisage a scenario where transactions made
	 * by members of this group <i>before</i> they became members of
	 * this group may not be extremely relevant. Hence, the network can
	 * also be formed accounting for the befriending date of friends.
	 * The default option is {@link NetworkCalculationModel#IGNORE_BEFRIEND_TIMING}
	 * which also results in faster calculation of networks with higher
	 * {@link AnomalousPurchaseDetector#D} values. Choosing
	 * {@link NetworkCalculationModel#ACCOUNT_FOR_BEFRIEND_TIMING} would
	 * account for befriend timing and calculate anomalous transactions
	 * accordingly.</p>
	 */
	static final NetworkCalculationModel NETWORK_MODEL = NetworkCalculationModel.IGNORE_BEFRIEND_TIMING;

	/**
	 * The number of minimum purchases any network should have to even
	 * check for anomalous transactions. Defaults to a value of 2.
	 */
	static final int MINIMUM_NUMBER_OF_PURCHASES = 2;
	/**
	 * The number of standard deviations above the mean of the most
	 * recent {@link AnomalousPurchaseDetector#T} transactions a
	 * particular transaction be to be considered anomalous.
	 */
	static final int NUMBER_OF_STANDARD_DEVIATIONS_FOR_CUTOFF = 3;
	private static final int MAX_ERRORS = 25;
	/**
	 * The variable which specifies how many degrees the network around
	 * each user must be calculated for. Defaults to 2, and is read from
	 * the JSON file if the appropriate JSON object is encountered.
	 */
	static int D = 2;
	/**
	 * The variable which specifies the number of transactions in the recent
	 * past within a network that should be considered for anomaly calculation.
	 */
	static int T = 50;
	/**
	 * Master ArrayList that holds all the {@link User} objects of the program.
	 * The purchases made by these users are also stored inside their own objects.
	 */
	static List<User> users = new ArrayList<>();
	/**
	 * Internal variables that are used to keep count, format dates and write files.
	 */
	private static long purchaseCount = 0, numberOfAnomalies = 0;
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static BufferedWriter writeOutputFile, errorLogBuffer;
	private static int nErrors = 0;
	private static String errorLogFileName = "error_log.txt";


	/**
	 * The main function takes three arguments for the file paths
	 * and performs the network building and anomalous purchase detection.
	 *
	 * @param args The three expected arguments are the paths to the
	 *             batch-log.json file (which builds the base
	 *             network of users and their purchases, the
	 *             stream-log.json file (which emulates the
	 *             incoming stream of purchases and network
	 *             updates) and the output
	 *             flagged-purchases.json file where all
	 *             flagged purchases will be written.
	 * @throws IOException    IOException happens if the files are not found in the path.
	 * @throws ParseException ParseException happens if the JSON format is severely malformed.
	 */
	public static void main(String[] args) throws IOException, ParseException {
		final String batchFilePath = args[0];
		final String streamFilePath = args[1];
		final String outputFilePath = args[2];
		final long startTime = System.currentTimeMillis();

		try {
			readJsonStream(
					new FileInputStream(
							batchFilePath), false);
			writeOutputFile = new BufferedWriter(
					new FileWriter(outputFilePath));

			readJsonStream(
					new FileInputStream(
							streamFilePath), true);
			writeOutputFile.close();
		} catch (FileNotFoundException fo) {
			errorPrint("ERROR! One of the input files was not found in the specified path.", fo);
		}
		System.out.println("Found " + numberOfAnomalies + " anomalies.");

		System.out.println("Anomaly check took " +
				(System.currentTimeMillis() - startTime) + " milliseconds");
		if (errorLogBuffer != null)
			errorLogBuffer.close();
	}

	/**
	 * This function reads the {@link InputStream} file and decodes the
	 * json array one line at a time.
	 * It does assume that the json file is delimited by newline characters.
	 * It then classifies the type of information and populates the appropriate
	 * parameters/object arrays with the information. If specified by the boolean
	 * argument, it will also check each incoming purchase to find if it
	 * is an anomaly.
	 *
	 * @param in              The FileInputStream object that refers
	 *                        to the json file to be read.
	 * @param checkForAnomaly Boolean argument which if true would make the
	 *                        function call the checkForAnomaly method
	 *                        to check if purchases are anomalous.
	 * @throws IOException IOException is thrown if the file reading is interrupted.
	 */
	static void readJsonStream(
			InputStream in,
			boolean checkForAnomaly) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		String strLine;
		Gson gson = new GsonBuilder().create();
		while ((strLine = br.readLine()) != null)
			try {
				// Parse each line as a separate JSON string,
				// easier to deal with discrepancies between
				// the json format and guidelines
				JsonEventUnit jsonEvent = gson.fromJson(strLine, JsonEventUnit.class);
				boolean befriendFlag = true;
				if (jsonEvent == null)
					continue;
				if (jsonEvent.event_type != null) {
					final long timestamp = dateFormat.parse(jsonEvent.timestamp).getTime();
					switch (jsonEvent.event_type) {
						case "purchase":
							final int user_id = Integer.parseInt(jsonEvent.id);
							final double purchase_amount = Double.parseDouble(jsonEvent.amount);
							purchaseCount++;
							if (checkForAnomaly)
								checkForAnomaly(user_id, purchase_amount, timestamp);
							addPurchase(user_id, purchase_amount, timestamp);
							break;
						case "unfriend":
							befriendFlag = false;
						case "befriend":
							final int user_id1 = Integer.parseInt(jsonEvent.id1);
							final int user_id2 = Integer.parseInt(jsonEvent.id2);
							addOrRemoveFriendship(user_id1, user_id2, befriendFlag, timestamp);
						default:
					}
				} else if (jsonEvent.D != null) {
					D = Integer.parseInt(jsonEvent.D);
					if (jsonEvent.T != null)
						T = Integer.parseInt(jsonEvent.T);
				}
			} catch (ParseException pe) {
				errorPrint("Failed to parse line: " + strLine, pe);
			} catch (Exception e) {
				errorPrint("Error reading line \"" + strLine + "\"", e);
			}
		br.close();
	}

	/**
	 * Function handles non-fatal errors in a centralized fashion.
	 *
	 * @param message The message to be displayed and logged in the file.
	 * @param e       The exception caused.
	 */
	static void errorPrint(String message, Exception e) {
		try {
			if (nErrors == 0) {
				errorLogFileName = "error_log_" +
						(new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()))
						+ ".txt";
				errorLogBuffer = new BufferedWriter(
						new FileWriter(errorLogFileName));

			}
			if (e == null)
				errorLogBuffer.write(message + "\n");
			else {
				errorLogBuffer.write(message + "\n");
				errorLogBuffer.write("\t" + e.toString() + "\n");
				errorLogBuffer.write("\t" + e.getLocalizedMessage() + "\n");
			}
		} catch (IOException ioe) {
			// What should we do if we can't even write an error log?
		}
		nErrors++;

		if (nErrors < MAX_ERRORS) {
			System.out.println(message);
		} else if (nErrors == MAX_ERRORS) {
			System.out.println("Program already displayed " +
					nErrors +
					" messages, suppressing display of " +
					"further messages. Check " +
					errorLogFileName + " for details.");
		}


	}

	/**
	 * This is the function that checks for anomalous purchases.
	 * First it takes the incoming user idsOfUsersInThisList who made the purchase
	 * and builds their nearby network. It uses the {@link AnomalousPurchaseDetector#D} parameter
	 * and builds a unique list of user-ids that form the
	 * D<sup>th</sup> order network around the main user idsOfUsersInThisList.
	 * Then it collects all valid purchases made by each of these
	 * users (see {@link UserNetworkBuilder#getValidPurchases(int)}) and finds the most recent
	 * T transactions. Finally, if it finds that  purchase to be more
	 * than {@link AnomalousPurchaseDetector#NUMBER_OF_STANDARD_DEVIATIONS_FOR_CUTOFF} standard deviations
	 * above the mean of these transactions, its flagged as anomalous
	 * and written to the output json file.
	 *
	 * @param user_id         The user idsOfUsersInThisList associated with the incoming purchase.
	 * @param purchase_amount The amount of the incoming purchase.
	 * @param timestamp       The timestamp associated with the incoming purchase.
	 * @throws IOException If there was a problem reading the file or writing to the file.
	 */
	static void checkForAnomaly(
			final int user_id,
			final double purchase_amount,
			final long timestamp) throws IOException {
		checkAndCreateUserIfNeeded(user_id);
		UserNetworkBuilder users_in_this_network = new UserNetworkBuilder(
				users.get(user_id).friends,
				users.get(user_id).befriendedTime,
				user_id);

		int nPurchasesInThisNetwork = 0;
		// This code block tries to only maintain T Purchases in memory, so that time
		// is not wasted sorting through lists of thousands of purchases when larger
		// networks are encountered.
		Purchase.ComparePurchaseDates purchaseComparator = new Purchase.ComparePurchaseDates();
		Purchase[] recentPurchasesInThisGroup = new Purchase[T];
		for (int i = 0; i < users_in_this_network.idsOfUsersInThisList.size(); i++) {
			List<Purchase> thisUsersPurchases = users_in_this_network.getValidPurchases(i);
			for (Purchase purchaseIterator : thisUsersPurchases) {
				if (nPurchasesInThisNetwork < (T - 1))
					recentPurchasesInThisGroup[nPurchasesInThisNetwork] = purchaseIterator;
				else if (nPurchasesInThisNetwork == (T - 1)) {
					recentPurchasesInThisGroup[nPurchasesInThisNetwork] = purchaseIterator;
					Arrays.sort(recentPurchasesInThisGroup, purchaseComparator);
				} else if (purchaseComparator.compare(
						purchaseIterator,
						recentPurchasesInThisGroup[0]) > 0) {
					recentPurchasesInThisGroup[0] = purchaseIterator;
					Arrays.sort(recentPurchasesInThisGroup, purchaseComparator);
				}
				nPurchasesInThisNetwork++;
			}
		}
		if (nPurchasesInThisNetwork >= MINIMUM_NUMBER_OF_PURCHASES) {
			int nPurchasesToConsider = nPurchasesInThisNetwork;
			if (nPurchasesToConsider > T) nPurchasesToConsider = T;
			final MeanSTDCutoff cutoffResult = new MeanSTDCutoff(
					recentPurchasesInThisGroup,
					nPurchasesToConsider);


			if (purchase_amount > cutoffResult.cutoff) {
				numberOfAnomalies++;
				String outputString =
						"{\"event_type\":\"purchase\", \"timestamp\":\"" +
								dateFormat.format(new Date(
										timestamp)) +
								"\", \"id\": \"" +
								Integer.toString(user_id) +
								"\", \"amount\": \"" +
								String.format("%.2f", purchase_amount) +
								"\", \"mean\": \"" +
								String.format("%.2f", cutoffResult.mean) +
								"\", \"sd\": \"" +
								String.format("%.2f", cutoffResult.std) +
								"\"}" + System.getProperty("line.separator");

				writeOutputFile.write(outputString);
			}


		}
	}

	/**
	 * Given a user idsOfUsersInThisList, this function first checks if
	 * this user-idsOfUsersInThisList exists in the {@link AnomalousPurchaseDetector#users}
	 * list already and if not, adds it to the list.
	 *
	 * @param user_id Incoming user idsOfUsersInThisList to check and add.
	 */
	static void checkAndCreateUserIfNeeded(
			final int user_id) {
		if (users.size() <= user_id) {
			for (int i = users.size(); i < user_id; i++)
				users.add(i, null);
			User newUser = new User();
			newUser.id = user_id;
			users.add(user_id, newUser);
		} else if (users.get(user_id) == null) {
			User newUser = new User();
			newUser.id = user_id;
			users.set(user_id, newUser);
		}
	}

	/**
	 * This function transactionally adds or removes
	 * friendships in the {@link AnomalousPurchaseDetector#users} list. Whether
	 * the action is to friend or unfriend is determined
	 * by the befriendFlag input. The timestamp is also
	 * recorded just in case future implementations want
	 * to take advantage of that information.
	 *
	 * @param id1          ID of User 1
	 * @param id2          ID of User 2
	 * @param befriendFlag Boolean if true, the action is to add friendship;
	 *                     if false , the action is to unfriend each other
	 * @param timestamp    timestamp of the action
	 */
	static void addOrRemoveFriendship(
			final int id1,
			final int id2,
			final boolean befriendFlag,
			final long timestamp) {
		checkAndCreateUserIfNeeded(id1);
		checkAndCreateUserIfNeeded(id2);
		User user1 = users.get(id1);
		User user2 = users.get(id2);
		if (befriendFlag) {
			user1.addAFriend(id2, timestamp);
			user2.addAFriend(id1, timestamp);
		} else {
			user1.removeAFriend(id2);
			user2.removeAFriend(id1);
		}
	}

	/**
	 * This function adds a purchase to a user's list
	 * of purchases. The number of purchases stored
	 * per user is capped at the value of {@link AnomalousPurchaseDetector#T} since
	 * we do not have any interest in more than {@link AnomalousPurchaseDetector#T}
	 * prior purchases even if a single user is
	 * the only friend of another user.
	 *
	 * @param user_id         ID of user making purchase
	 * @param purchase_amount Purchase amount
	 * @param timestamp       Purchase timestamp
	 */
	static void addPurchase(
			final int user_id,
			final double purchase_amount,
			final long timestamp) {
		checkAndCreateUserIfNeeded(user_id);
		User user = users.get(user_id);
		Purchase purchase = new Purchase();
		purchase.amount = purchase_amount;
		purchase.timestamp = timestamp;
		purchase.purchaseOrder = purchaseCount;
		purchase.user_id = user_id;
		final int nPurchases = user.purchases.size();
		if (nPurchases < T)
			user.purchases.add(purchase);
		else
			user.purchases.set(
					user.purchases.indexOf(
							Collections.min(
									user.purchases,
									new Purchase.ComparePurchaseDates())),
					purchase);
		users.set(user_id, user);
	}

	/**
	 * enum that stores different Network Calculation Models.
	 * More may be added in the future.
	 */
	static enum NetworkCalculationModel {
		IGNORE_BEFRIEND_TIMING,
		ACCOUNT_FOR_BEFRIEND_TIMING
	}

	/**
	 * A class that calculates and holds the mean, standard
	 * deviation and cutoff values to be used by the
	 * {@link AnomalousPurchaseDetector#checkForAnomaly(int, double, long)}
	 * function.
	 */
	static class MeanSTDCutoff {
		double mean = 0;
		double std = 0;
		double cutoff = 0;

		/**
		 * The constructor calculates the mean and standard
		 * deviation of an array of {@link Purchase} objects
		 * and returns a {@link MeanSTDCutoff} object containing
		 * the results.
		 *
		 * @param recentPurchasesInThisGroup Array of {@link Purchase} objects
		 * @param nPurchasesToConsider       Number of purchases to consider in the above array
		 */
		MeanSTDCutoff(Purchase[] recentPurchasesInThisGroup,
		              final int nPurchasesToConsider) {
			for (int i = 0; i < nPurchasesToConsider; i++)
				mean += recentPurchasesInThisGroup[i].amount;
			mean = mean / nPurchasesToConsider;
			for (int i = 0; i < nPurchasesToConsider; i++) {
				final double thisAmount = recentPurchasesInThisGroup[i].amount;
				std += (thisAmount - mean) * (thisAmount - mean);
			}
			std = Math.sqrt(std / nPurchasesToConsider);
			cutoff = mean + NUMBER_OF_STANDARD_DEVIATIONS_FOR_CUTOFF * std;
		}
	}

	/**
	 * The class used by the {@link Gson} object to read the
	 * json file line by line. These are the only elements
	 * expected to be read in from the json file.
	 */
	static class JsonEventUnit {
		String D;
		String T;
		String event_type;
		String timestamp;
		String id;
		String id1;
		String id2;
		String amount;
	}

	/**
	 * The class that represents users. Each
	 * instance of {@link User} stores the user {@link User#id},
	 * a list of {@link User#friends} ids, a corresponding list of
	 * time of befriending each of these friends, and a list of
	 * {@link Purchase} objects holding the most recent {@link AnomalousPurchaseDetector#T}
	 * purchases made by the user. It also includes methods that
	 * can be used to add or remove friends to the user.
	 */
	static class User {
		int id;
		ArrayList<Integer> friends = new ArrayList<>();
		ArrayList<Long> befriendedTime = new ArrayList<>();
		ArrayList<Purchase> purchases = new ArrayList<>();

		User() {
			purchases.ensureCapacity(T);
		}

		void addAFriend(final int user_id, final long time_stamp) {
			friends.add(user_id);
			befriendedTime.add(time_stamp);
		}

		void removeAFriend(final int user_id) {
			final int friendIndex = friends.indexOf(user_id);
			if (friendIndex == -1) {
				errorPrint(
						"Warning: JSON asked to unfriend " +
								id + " and " + user_id +
								" but they were not friends to begin with.", null);
				return;
			}
			friends.remove(friendIndex);
			befriendedTime.remove(friendIndex);
		}
	}

	/**
	 * The class that represents purchases. Each instance of
	 * {@link Purchase} stores information pertaining to the
	 * purchase such as the ID of the user, the amount, the
	 * time stamp and the order in which the purchase was
	 * recorded. It also implements a custom {@link Comparator}
	 * that can compare purchases based on different criteria
	 * as desired.
	 */
	static class Purchase {
		int user_id;
		double amount;
		long timestamp;
		long purchaseOrder;

		/**
		 * A custom {@link Comparator} class which compares {@link Purchase}
		 * objects first by their timestamps, and if they match, then
		 * by the order in which they were encountered in the json file.
		 */
		public static class ComparePurchaseDates implements Comparator<Purchase> {
			@Override
			public int compare(Purchase o1, Purchase o2) {
				if (o1.timestamp == o2.timestamp) {
					final long o1order = o1.purchaseOrder;
					final long o2order = o2.purchaseOrder;
					if (o1order == o2order) return 0;
					if (o1order > o2order)
						return 1;
					else
						return -1;
				} else if (o1.timestamp > o2.timestamp) return 1;
				else return -1;
			}
		}
	}


	/**
	 * A class that handles building and storing the local user network.
	 */
	static class UserNetworkBuilder {
		List<Integer> idsOfUsersInThisList;
		List<Long> befriendedDate;

		/**
		 * The constructor builds the unique list of users in the
		 * D<sup>th</sup> degree social network around the given
		 * user id (excluding that user). The exact implementation
		 * depends on the state of the {@link AnomalousPurchaseDetector#NETWORK_MODEL}
		 * constant.
		 *
		 * @param userids        {@link ArrayList} of user ids who form the
		 *                       immediate network around the user (D=1).
		 * @param befriendedTime Time at which these users were befriended.
		 * @param user_id        ID of the original root user (to exclude from the network).
		 */
		UserNetworkBuilder(List<Integer> userids,
		                   List<Long> befriendedTime, int user_id) {


			switch (NETWORK_MODEL) {
				case IGNORE_BEFRIEND_TIMING:

					HashSet<Integer> unique_network_members = new HashSet<>(userids);
					for (int i = 2; i <= D; i++) {
						final HashSet<Integer> temp_list = new HashSet<>(unique_network_members);
						for (int user_in_the_group : temp_list)
							unique_network_members.addAll(users.get(user_in_the_group).friends);
					}
					unique_network_members.remove(user_id);
					idsOfUsersInThisList = new ArrayList<>(unique_network_members);

					break;
				case ACCOUNT_FOR_BEFRIEND_TIMING:
					idsOfUsersInThisList = new ArrayList<>(userids);

					befriendedDate = new ArrayList<>(befriendedTime);
					for (int i = 2; i <= D; i++) {
						List<Integer> temp_list = new ArrayList<>(idsOfUsersInThisList);
						for (int user_in_the_group : temp_list) {
							this.addAll(
									users.get(user_in_the_group).friends,
									users.get(user_in_the_group).befriendedTime,
									user_id);
						}
					}
					break;
			}
		}

		/**
		 * Function adds all the given user ids to the list maintained
		 * within the object including (if specified by the
		 * {@link AnomalousPurchaseDetector#NETWORK_MODEL} parameter)
		 * the time of befriending.
		 *
		 * @param userids                {@link ArrayList} of user ids who need to be added.
		 * @param incomingBefriendedTime Time at which these users were befriended.
		 * @param original_user_id       ID of the original root user
		 *                               (to exclude from the network).
		 */
		void addAll(List<Integer> userids,
		            List<Long> incomingBefriendedTime,
		            final int original_user_id) {
			for (int i = 0; i < userids.size(); i++) {
				final int incoming_id = userids.get(i);
				if (incoming_id == original_user_id)
					continue;

				if (idsOfUsersInThisList.contains(incoming_id)) {
					final long dateRecorded =
							befriendedDate.get(
									idsOfUsersInThisList.indexOf(
											incoming_id));
					long dateIncoming = incomingBefriendedTime.get(i);
					if (dateRecorded > dateIncoming)
						befriendedDate.set(
								idsOfUsersInThisList.indexOf(incoming_id), dateIncoming);
				} else {
					idsOfUsersInThisList.add(incoming_id);
					befriendedDate.add(incomingBefriendedTime.get(i));
				}


			}
		}

		/**
		 * Function returns all the valid purchases associated with
		 * this network. "Validity" is simple if
		 * {@link AnomalousPurchaseDetector#NETWORK_MODEL} is set to
		 * {@link NetworkCalculationModel#IGNORE_BEFRIEND_TIMING}, but
		 * is a little more involved if the value is set to
		 * {@link NetworkCalculationModel#ACCOUNT_FOR_BEFRIEND_TIMING}.
		 *
		 * @param index Index of the user_id in the list
		 *              maintained within the object.
		 * @return {@link ArrayList} of valid {@link Purchase} objects.
		 */
		List<Purchase> getValidPurchases(int index) {
			ArrayList<Purchase> validPurchases = new ArrayList<>();
			final int thisUserID = idsOfUsersInThisList.get(index);
			List<Purchase> allPurchases = users.get(thisUserID).purchases;
			switch (NETWORK_MODEL) {
				case IGNORE_BEFRIEND_TIMING:
					validPurchases.addAll(allPurchases);
					break;
				case ACCOUNT_FOR_BEFRIEND_TIMING:
					final long thisUsersBefriendDate = befriendedDate.get(index);
					validPurchases.ensureCapacity(allPurchases.size());
					for (Purchase eachPurchase : allPurchases) {
						if (eachPurchase.timestamp > thisUsersBefriendDate)
							validPurchases.add(eachPurchase);
					}
					break;
			}
			return validPurchases;
		}

	}


}
