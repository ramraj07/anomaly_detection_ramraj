
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class AnomalousPurchaseDetector {

	private static final int MINIMUM_NUMBER_OF_PURCHASES = 2;
	private static final int NUMBER_OF_STANDARD_DEVIATIONS_FOR_CUTOFF = 3;
	private static final NetworkFormationModel NETWORK_MODEL = NetworkFormationModel.NETWORK_IGNORES_BEFRIEND_TIMING;
	private static int D = 2;
	private static int T = 50;
	private static List<User> users = new ArrayList<>();
	private static long purchaseCount = 0, numberOfAnomalies = 0;
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static BufferedWriter writeOutputFile;

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

		readJsonStream(
				new FileInputStream(
						batchFilePath), false);
		final long startTime = System.currentTimeMillis();
		writeOutputFile = new BufferedWriter(
				new FileWriter(outputFilePath));

		readJsonStream(
				new FileInputStream(
						streamFilePath), true);
		writeOutputFile.close();
		System.out.println("Found " + numberOfAnomalies + " anomalies.");

		System.out.println("Anomaly check took " +
				(System.currentTimeMillis() - startTime) + " milliseconds");
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
	 * @throws IOException    IOException is thrown if the file reading is interrupted.
	 * @throws ParseException If the JSON format is malformed.
	 */
	private static void readJsonStream(
			InputStream in,
			boolean checkForAnomaly) throws IOException, ParseException {
		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		String strLine;
		Gson gson = new GsonBuilder().create();
		while ((strLine = br.readLine()) != null)
			try {
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
					T = Integer.parseInt(jsonEvent.T);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		br.close();
	}

	/**
	 * This is the function that checks for anomalous purchases.
	 * First it takes the incoming user idsOfUsersInThisList who made the purchase
	 * and builds their nearby network. It uses the {@link AnomalousPurchaseDetector#D} parameter
	 * and builds a unique list of user-ids that form the
	 * D<sup>th</sup> order network around the main user idsOfUsersInThisList.
	 * Then it collects all valid purchases made by each of these
	 * users (see {@link UserAndJoinDate#getValidPurchases(int)}) and finds the most recent
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
	private static void checkForAnomaly(
			final int user_id,
			final double purchase_amount,
			final long timestamp) throws IOException {
		UserAndJoinDate local_group_ids = new UserAndJoinDate(
				users.get(user_id).friends,
				users.get(user_id).befriendedTime);
		for (int i = 2; i <= D; i++) {
			List<Integer> temp_list = new ArrayList<>(local_group_ids.idsOfUsersInThisList);
			for (int user_in_the_group : temp_list) {
				local_group_ids.addAll(
						users.get(user_in_the_group).friends,
						users.get(user_in_the_group).befriendedTime,
						user_id);
			}
		}
		int nPurchases = 0;

		Purchase.ComparePurchaseDates purchaseComparator = new Purchase.ComparePurchaseDates();
		Purchase[] recentPurchasesInThisGroup = new Purchase[T];
		for (int i = 0; i < local_group_ids.idsOfUsersInThisList.size(); i++) {
			List<Purchase> thisUsersPurchases = local_group_ids.getValidPurchases(i);
			for (Purchase purchaseIterator : thisUsersPurchases) {
				if (nPurchases < (T - 1))
					recentPurchasesInThisGroup[nPurchases] = purchaseIterator;
				else if (nPurchases == (T - 1)) {
					recentPurchasesInThisGroup[nPurchases] = purchaseIterator;
					Arrays.sort(recentPurchasesInThisGroup, purchaseComparator);
				} else if (purchaseComparator.compare(
						purchaseIterator,
						recentPurchasesInThisGroup[0]) > 0) {
					recentPurchasesInThisGroup[0] = purchaseIterator;
					Arrays.sort(recentPurchasesInThisGroup, purchaseComparator);
				}
				nPurchases++;
			}
		}
		if (nPurchases >= MINIMUM_NUMBER_OF_PURCHASES) {
			int nPurchasesToConsider = nPurchases;
			if (nPurchasesToConsider > T) nPurchasesToConsider = T;
			final MeanSTDCutoff cutoffResult = getCutOffValue(
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
	private static void checkAndCreateUserIfNeeded(
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
	private static void addOrRemoveFriendship(
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
	private static void addPurchase(
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
	 * Function that calculates the mean and standard
	 * deviation of an array of {@link Purchase} objects
	 * and returns a {@link MeanSTDCutoff} object containing
	 * the results.
	 *
	 * @param recentPurchasesInThisGroup Array of {@link Purchase} objects
	 * @param nPurchasesToConsider       Number of purchases to consider in the above array
	 * @return {@link MeanSTDCutoff} object containing the mean, std and cutoff results.
	 */
	private static MeanSTDCutoff getCutOffValue(
			Purchase[] recentPurchasesInThisGroup,
			final int nPurchasesToConsider) {
		double mean = 0;
		double std = 0;
		for (int i = 0; i < nPurchasesToConsider; i++)
			mean += recentPurchasesInThisGroup[i].amount;
		mean = mean / nPurchasesToConsider;
		for (int i = 0; i < nPurchasesToConsider; i++) {
			final double thisAmount = recentPurchasesInThisGroup[i].amount;
			std += (thisAmount - mean) * (thisAmount - mean);
		}
		std = Math.sqrt(std / nPurchasesToConsider);
		/*SummaryStatistics summ = new SummaryStatistics();
		for (int i = 0; i < nPurchasesToConsider; i++)
			summ.addValue(recentPurchasesInThisGroup[i].amount);
		MeanSTDCutoff result = new MeanSTDCutoff();
		result.mean = summ.getMean();
		result.std = summ.getStandardDeviation();*/
		MeanSTDCutoff result = new MeanSTDCutoff();

		result.mean = mean;
		result.std = std;
		result.cutoff = mean + (std * NUMBER_OF_STANDARD_DEVIATIONS_FOR_CUTOFF);
		return result;
	}

	private static enum NetworkFormationModel {
		NETWORK_IGNORES_BEFRIEND_TIMING,
		NETWORK_ACCOUNTS_BEFRIEND_TIMING
	}

	/**
	 * A class that holds mean, standard deviation and
	 * cutoff values to be used by the
	 * {@link AnomalousPurchaseDetector#getCutOffValue(Purchase[], int)} function.
	 */
	private static class MeanSTDCutoff {
		double mean = 0;
		double std = 0;
		double cutoff = 0;
	}

	/**
	 * The class used by the {@link Gson} object to read the
	 * json file line by line. These are the only elements
	 * expected to be read in from the json file.
	 */
	private static class JsonEventUnit {
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
	private static class User {
		int id;
		ArrayList<Integer> friends = new ArrayList<>();
		ArrayList<Long> befriendedTime = new ArrayList<>();
		ArrayList<Purchase> purchases = new ArrayList<>();

		User() {
			purchases.ensureCapacity(T);
		}

		public void addAFriend(final int user_id, final long time_stamp) {
			friends.add(user_id);
			befriendedTime.add(time_stamp);
		}

		public void removeAFriend(final int user_id) {
			final int friendIndex = friends.indexOf(user_id);
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
	private static class Purchase {
		int user_id;
		double amount;
		long timestamp;
		long purchaseOrder;

		/**
		 * A custom {@link Comparator} which compares {@link Purchase}
		 * objects first by their timestamps, and if they match, then
		 * by the order in which they were purchased.
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
	 * A class that
	 */
	private static class UserAndJoinDate {
		List<Integer> idsOfUsersInThisList;
		List<Long> befriendedDate;

		UserAndJoinDate(List<Integer> userids,
		                List<Long> befriendedTime) {
			idsOfUsersInThisList = new ArrayList<>(userids);
			if (NETWORK_MODEL == NetworkFormationModel.NETWORK_ACCOUNTS_BEFRIEND_TIMING)
				befriendedDate = new ArrayList<>(befriendedTime);
		}

		void addAll(List<Integer> userids,
		            List<Long> incomingBefriendedTime,
		            final int original_user_id) {
			for (int i = 0; i < userids.size(); i++) {
				final int incoming_id = userids.get(i);
				if (incoming_id == original_user_id)
					continue;

				switch (NETWORK_MODEL) {
					case NETWORK_IGNORES_BEFRIEND_TIMING:
						if (!idsOfUsersInThisList.contains(incoming_id))
							idsOfUsersInThisList.add(incoming_id);

						break;
					case NETWORK_ACCOUNTS_BEFRIEND_TIMING:
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
						break;
				}


			}
		}


		List<Purchase> getValidPurchases(int index) {
			ArrayList<Purchase> validPurchases = new ArrayList<>();
			final int thisUserID = idsOfUsersInThisList.get(index);
			List<Purchase> allPurchases = users.get(thisUserID).purchases;
			switch (NETWORK_MODEL) {
				case NETWORK_IGNORES_BEFRIEND_TIMING:
					validPurchases.addAll(allPurchases);
					break;
				case NETWORK_ACCOUNTS_BEFRIEND_TIMING:
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
