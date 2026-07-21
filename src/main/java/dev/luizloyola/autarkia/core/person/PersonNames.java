package dev.luizloyola.autarkia.core.person;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Names for new persons, deterministic for a given {@link RandomGenerator} so assignment is
 * unit-testable. A placeholder starter set of common English names, separate from skin choice; only
 * the given name is used today.
 *
 * <p>Sources: US SSA given names by long-run popularity (1880–2008); US Census surnames.
 */
public final class PersonNames {
    private PersonNames() {}

    static final List<String> FIRST_NAMES = List.of(
            "Aaron", "Adam", "Agnes", "Albert", "Alexander", "Alfred", "Alice", "Amanda",
            "Amy", "Andrew", "Angela", "Ann", "Anna", "Annie", "Anthony", "Arthur",
            "Ashley", "Barbara", "Benjamin", "Bertha", "Bessie", "Betty", "Billy", "Brandon",
            "Brenda", "Brian", "Carl", "Carol", "Carolyn", "Carrie", "Catherine", "Charles",
            "Charlie", "Christina", "Christine", "Christopher", "Clara", "Clarence", "Cynthia", "Daniel",
            "David", "Deborah", "Debra", "Dennis", "Diane", "Donald", "Donna", "Doris",
            "Dorothy", "Douglas", "Earl", "Edith", "Edna", "Edward", "Eleanor", "Elizabeth",
            "Ella", "Ellen", "Elsie", "Emily", "Emma", "Eric", "Ernest", "Esther",
            "Ethel", "Eugene", "Eva", "Evelyn", "Florence", "Frances", "Francis", "Frank",
            "Fred", "Frederick", "Gary", "George", "Gerald", "Gertrude", "Gladys", "Gloria",
            "Grace", "Gregory", "Harold", "Harry", "Hazel", "Heather", "Helen", "Henry",
            "Herbert", "Howard", "Ida", "Irene", "Jack", "Jacob", "James", "Jane",
            "Janet", "Jason", "Jean", "Jeffrey", "Jennifer", "Jerry", "Jesse", "Jessica",
            "Jessie", "Joan", "Joe", "John", "Jonathan", "Jose", "Joseph", "Josephine",
            "Joshua", "Joyce", "Judith", "Julia", "Julie", "Justin", "Karen", "Katherine",
            "Kathleen", "Kathryn", "Kelly", "Kenneth", "Kevin", "Kimberly", "Larry", "Laura",
            "Lawrence", "Lee", "Leonard", "Leslie", "Lillian", "Lillie", "Linda", "Lisa",
            "Lois", "Louis", "Louise", "Mabel", "Margaret", "Maria", "Marie", "Marion",
            "Mark", "Martha", "Mary", "Matthew", "Melissa", "Michael", "Michelle", "Mildred",
            "Minnie", "Myrtle", "Nancy", "Nathan", "Nellie", "Nicholas", "Nicole", "Pamela",
            "Patricia", "Patrick", "Paul", "Pearl", "Peter", "Rachel", "Ralph", "Raymond",
            "Rebecca", "Richard", "Robert", "Roger", "Ronald", "Rose", "Roy", "Ruby",
            "Russell", "Ruth", "Ryan", "Samantha", "Samuel", "Sandra", "Sara", "Sarah",
            "Scott", "Sharon", "Shirley", "Stanley", "Stephanie", "Stephen", "Steven", "Susan",
            "Terry", "Theresa", "Thomas", "Timothy", "Virginia", "Walter", "William", "Willie");

    /**
     * Common surnames. Reserved for when persons gain family/lineage names — see
     * {@link #randomSurname(RandomGenerator)}; not yet used anywhere.
     */
    static final List<String> SURNAMES = List.of(
            "Adams", "Alexander", "Allen", "Alvarez", "Anderson", "Bailey", "Baker", "Barnes",
            "Bell", "Bennett", "Black", "Brooks", "Brown", "Bryant", "Burns", "Butler",
            "Campbell", "Carter", "Castillo", "Chavez", "Clark", "Cole", "Coleman", "Collins",
            "Cook", "Cooper", "Cox", "Crawford", "Cruz", "Davis", "Diaz", "Dixon",
            "Edwards", "Ellis", "Evans", "Fisher", "Flores", "Ford", "Foster", "Freeman",
            "Garcia", "Gibson", "Gomez", "Gonzales", "Gonzalez", "Gordon", "Graham", "Gray",
            "Green", "Griffin", "Gutierrez", "Hall", "Hamilton", "Harris", "Harrison", "Hayes",
            "Henderson", "Henry", "Hernandez", "Hicks", "Hill", "Howard", "Hughes", "Hunt",
            "Hunter", "Jackson", "James", "Jenkins", "Jimenez", "Johnson", "Jones", "Jordan",
            "Kelly", "Kennedy", "Kim", "King", "Lee", "Lewis", "Long", "Lopez",
            "Marshall", "Martin", "Martinez", "Mason", "McDonald", "Mendoza", "Miller", "Mitchell",
            "Moore", "Morales", "Morgan", "Morris", "Murphy", "Murray", "Myers", "Nelson",
            "Nguyen", "Olson", "Ortiz", "Owens", "Palmer", "Parker", "Patterson", "Perez",
            "Perry", "Peterson", "Phillips", "Porter", "Powell", "Price", "Ramirez", "Ramos",
            "Reed", "Reyes", "Reynolds", "Richardson", "Rivera", "Roberts", "Robertson", "Robinson",
            "Rodriguez", "Rogers", "Romero", "Ross", "Ruiz", "Russell", "Sanchez", "Sanders",
            "Scott", "Shaw", "Simmons", "Simpson", "Smith", "Snyder", "Stevens", "Stewart",
            "Sullivan", "Taylor", "Thomas", "Thompson", "Torres", "Tucker", "Turner", "Vasquez",
            "Wagner", "Walker", "Wallace", "Ward", "Washington", "Watson", "Webb", "Wells",
            "West", "White", "Williams", "Wilson", "Wood", "Woods", "Wright", "Young");

    public static String random(RandomGenerator random) {
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
    }

    /**
     * A random surname. Reserved for a future family-name system; unused for now.
     */
    public static String randomSurname(RandomGenerator random) {
        return SURNAMES.get(random.nextInt(SURNAMES.size()));
    }
}
